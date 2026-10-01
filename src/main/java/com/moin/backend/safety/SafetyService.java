package com.moin.backend.safety;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.moin.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;

/** 차단은 양방향으로 콘텐츠 노출을 막고 인증 횟수·스트릭 집계는 유지 */
@Service @RequiredArgsConstructor
public class SafetyService {
 public static final String TERMS_VERSION="2026-10-01";
 private final UserBlockRepository blocks;
 private final ContentReportRepository reports;
 private final UserRepository users;
 public Set<Long> blocked(Long viewer) {
  var result = blocks.findByOwnerIdOrTargetId(viewer,viewer).stream().map(b -> b.getOwnerId().equals(viewer)?b.getTargetId():b.getOwnerId()).collect(Collectors.toSet());
  result.addAll(hidden(viewer,"USER"));
  return result;
 }
 public Set<Long> hidden(Long viewer,String kind) {
  return reports.findByReporterIdAndKind(viewer,kind).stream().map(ContentReport::getTargetId).collect(Collectors.toSet());
 }
 public void requireTerms(Long id) {
  var u=users.findById(id).orElseThrow();
  if (!TERMS_VERSION.equals(u.getTermsVersion())) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"이용약관과 개인정보 안내를 확인해주세요");
 }
 /** 게시 전 명백한 금지 문구 차단, 우회 표현·영상은 신고와 운영자 검토로 보완 */
 public void validateText(String text) {
  String normalized=text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]", "");
  if (List.of("아동음란물","성착취물판매","불법촬영물판매","죽여버릴","childporn","kill yourself").stream().map(s -> s.replace(" ", "")).anyMatch(normalized::contains))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"안전한 이용을 위해 이 내용은 게시할 수 없어요");
 }
}
