package com.moin.backend.safety;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.moin.backend.user.*;
import com.moin.backend.group.GroupMemberRepository;
import com.moin.backend.checkin.*;
import com.moin.backend.storage.FileDeletionService;
import lombok.RequiredArgsConstructor;

@RestController @RequiredArgsConstructor
public class SafetyController {
 private final UserRepository users;
 private final PushDeviceRepository devices;
 private final UserBlockRepository blocks;
 private final ContentReportRepository reports;
 private final GroupMemberRepository members;
 private final CheckInRepository checkIns;
 private final CheckInCommentRepository comments;
 private final FileDeletionService files;
 private final Clock clock;
 @Value("${moin.moderator-user-ids:}") private String moderatorIds;
 public record Terms(String version,boolean accepted,boolean ageConfirmed) {}
 @GetMapping("/me/terms") public Terms terms(@RequestAttribute("userId") Long id) {
  return new Terms(SafetyService.TERMS_VERSION,SafetyService.TERMS_VERSION.equals(users.findById(id).orElseThrow().getTermsVersion()),true);
 }
 @PostMapping("/me/terms") @Transactional @ResponseStatus(HttpStatus.NO_CONTENT)
 public void accept(@RequestAttribute("userId") Long id,@RequestBody Terms body) {
  if (!body.accepted() || !body.ageConfirmed() || !SafetyService.TERMS_VERSION.equals(body.version())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"필수 안내와 연령을 확인해주세요");
  var u=users.lockById(id).orElseThrow(); u.setTermsVersion(body.version()); u.setTermsAcceptedAt(clock.instant());
 }
 public record BlockView(Long userId,String nickname) {}
 @GetMapping("/me/blocks") public List<BlockView> blocked(@RequestAttribute("userId") Long id) {
  return blocks.findByOwnerId(id).stream().map(b -> new BlockView(b.getTargetId(),users.findById(b.getTargetId()).map(User::getNickname).orElse("탈퇴한 사용자"))).toList();
 }
 @PutMapping("/me/blocks/{target}") @Transactional @ResponseStatus(HttpStatus.NO_CONTENT)
 public void block(@RequestAttribute("userId") Long id,@PathVariable Long target) {
  users.lockById(id).orElseThrow();
  if (id.equals(target) || !users.existsById(target)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  boolean shared=members.findByUserId(id).stream().anyMatch(m -> members.findByGroupIdAndUserId(m.getGroupId(),target).isPresent());
  if (!shared) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"같은 그룹의 사용자만 차단할 수 있어요");
  if (!blocks.existsByOwnerIdAndTargetId(id,target)) blocks.save(new UserBlock(id,target));
 }
 @DeleteMapping("/me/blocks/{target}") @Transactional @ResponseStatus(HttpStatus.NO_CONTENT)
 public void unblock(@RequestAttribute("userId") Long id,@PathVariable Long target) { blocks.deleteByOwnerIdAndTargetId(id,target); }
 public record Report(@NotNull Long groupId,@Pattern(regexp="CHECK_IN|COMMENT|USER") @NotNull String kind,@NotNull Long targetId,@NotBlank @Size(max=500) String reason) {}
 @PostMapping("/reports") @Transactional @ResponseStatus(HttpStatus.CREATED)
 public Map<String,Long> report(@RequestAttribute("userId") Long id,@Valid @RequestBody Report body) {
  if (members.findByGroupIdAndUserId(body.groupId(),id).isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  Long author;
  if (body.kind().equals("CHECK_IN")) {
   var c=checkIns.findById(body.targetId()).filter(v -> v.getGroupId().equals(body.groupId())).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); author=c.getUserId();
  } else if (body.kind().equals("COMMENT")) {
   var c=comments.findById(body.targetId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
   if (checkIns.findById(c.getCheckInId()).filter(v -> v.getGroupId().equals(body.groupId())).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND); author=c.getUserId();
  } else {
   if (members.findByGroupIdAndUserId(body.groupId(),body.targetId()).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND); author=body.targetId();
  }
  if (author.equals(id)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"본인 콘텐츠는 신고할 수 없어요");
  users.lockById(id).orElseThrow();
  if (reports.existsByReporterIdAndKindAndTargetId(id,body.kind(),body.targetId())) throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 접수한 신고예요");
  if (reports.countByReporterIdAndStatus(id,"OPEN")>=50) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"접수한 신고의 처리를 기다려주세요");
  var r=new ContentReport(); r.setReporterId(id); r.setTargetUserId(author); r.setGroupId(body.groupId()); r.setTargetId(body.targetId()); r.setKind(body.kind()); r.setReason(body.reason()); r.setCreatedAt(clock.instant());
  return Map.of("id",reports.save(r).getId());
 }
 private void moderator(Long id) {
  if (Arrays.stream(moderatorIds.split(",")).map(String::trim).noneMatch(id.toString()::equals)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
 }
 @GetMapping("/moderation/reports") public List<ReviewItem> queue(@RequestAttribute("userId") Long id) {
  moderator(id);
  return reports.findByStatusOrderByCreatedAtAsc("OPEN",PageRequest.of(0,100)).stream().map(r -> new ReviewItem(r,
   r.getKind().equals("CHECK_IN") ? checkIns.findById(r.getTargetId()).map(CheckIn::getVideoUrl).orElse(null) : null,
   r.getKind().equals("COMMENT") ? comments.findById(r.getTargetId()).map(CheckInComment::getBody).orElse(null) : null)).toList();
 }
 public record ReviewItem(ContentReport report,String videoUrl,String comment) {}
 public record Decision(@Pattern(regexp="DISMISS|REMOVE|SUSPEND") @NotNull String action) {}
 /** 운영자만 신고를 종결, 삭제된 영상은 파일 삭제 대기열에도 추가 */
 @PostMapping("/moderation/reports/{reportId}") @Transactional @ResponseStatus(HttpStatus.NO_CONTENT)
 public void resolve(@RequestAttribute("userId") Long id,@PathVariable Long reportId,@Valid @RequestBody Decision body) {
  moderator(id); var r=reports.findById(reportId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  if (!r.getStatus().equals("OPEN")) throw new ResponseStatusException(HttpStatus.CONFLICT);
  if (body.action().equals("REMOVE")) {
   if (r.getKind().equals("CHECK_IN")) checkIns.findById(r.getTargetId()).ifPresent(c -> { files.enqueue(c.getVideoUrl()); c.removeVideo(); });
   else if (r.getKind().equals("COMMENT")) comments.deleteById(r.getTargetId());
   else throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"사용자 신고는 이용 정지 또는 기각을 선택해주세요");
  }
  if (body.action().equals("SUSPEND")) { users.findById(r.getTargetUserId()).ifPresent(u -> u.setSuspended(true)); devices.deleteByUserId(r.getTargetUserId()); }
  r.setStatus(body.action()); r.setReviewerId(id); r.setResolvedAt(clock.instant());
 }
}
