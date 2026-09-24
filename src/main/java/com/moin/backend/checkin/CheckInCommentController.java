package com.moin.backend.checkin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.moin.backend.group.GroupService;
import com.moin.backend.period.PeriodService;
import com.moin.backend.user.User;
import com.moin.backend.user.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** 그룹 멤버만 인증 댓글 조회·작성 가능, URL의 그룹과 인증 소속을 함께 검증 */
@RestController
@RequestMapping("/groups/{groupId}/check-ins/{checkInId}/comments")
@RequiredArgsConstructor
public class CheckInCommentController {
	private static final int PAGE_SIZE = 30;
	private final CheckInCommentRepository comments;
	private final CheckInRepository checkIns;
	private final GroupService groups;
	private final UserRepository users;
	private final PeriodService periods;

	public record CreateComment(@NotBlank(message = "댓글을 입력해주세요")
			@Size(max = 500, message = "댓글은 500자까지 입력할 수 있어요") String body) {}
	public record CommentView(Long id, Long userId, String nickname, String avatarUrl, String body, Instant createdAt) {}
	public record CommentPage(List<CommentView> items, Long nextCursor) {}

	/** 최신순 30개와 다음 커서, 새 댓글이 추가돼도 이전 페이지의 기준은 유지 */
	@GetMapping
	@Transactional(readOnly = true)
	public CommentPage list(@RequestAttribute("userId") Long userId, @PathVariable("groupId") Long groupId,
			@PathVariable("checkInId") Long checkInId, @RequestParam(value = "before", required = false) Long before) {
		checkAccess(groupId, checkInId, userId);
		if (before != null && before <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "댓글 위치를 확인해주세요");
		List<CheckInComment> found = comments.findByCheckInIdAndIdLessThanOrderByIdDesc(
				checkInId, before == null ? Long.MAX_VALUE : before, PageRequest.of(0, PAGE_SIZE + 1));
		List<CheckInComment> page = found.stream().limit(PAGE_SIZE).toList();
		Map<Long, User> authors = users.findAllById(page.stream().map(CheckInComment::getUserId).distinct().toList())
				.stream().collect(Collectors.toMap(User::getId, Function.identity()));
		return new CommentPage(page.stream().map(c -> view(c, authors.get(c.getUserId()))).toList(),
				found.size() > PAGE_SIZE ? page.get(PAGE_SIZE - 1).getId() : null);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	public CommentView create(@RequestAttribute("userId") Long userId, @PathVariable("groupId") Long groupId,
			@PathVariable("checkInId") Long checkInId, @Valid @RequestBody CreateComment request) {
		checkAccess(groupId, checkInId, userId);
		String body = request.body().strip();
		if (body.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "댓글을 입력해주세요");
		CheckInComment saved = comments.save(new CheckInComment(checkInId, userId, body, periods.now()));
		return view(saved, users.findById(userId).orElseThrow());
	}

	private void checkAccess(Long groupId, Long checkInId, Long userId) {
		groups.membership(groupId, userId);
		checkIns.findById(checkInId).filter(c -> c.getGroupId().equals(groupId))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "인증을 찾을 수 없어요"));
	}

	private CommentView view(CheckInComment c, User author) {
		return new CommentView(c.getId(), c.getUserId(), author == null ? "탈퇴한 사용자" : author.getNickname(),
				author == null ? null : author.getAvatarUrl(), c.getBody(), c.getCreatedAt());
	}
}
