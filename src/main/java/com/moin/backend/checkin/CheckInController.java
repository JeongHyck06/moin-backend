package com.moin.backend.checkin;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.moin.backend.group.Group;
import com.moin.backend.group.GroupMember;
import com.moin.backend.group.GroupService;
import com.moin.backend.group.GroupService.GroupCard;
import com.moin.backend.group.GroupService.MemberStatus;
import com.moin.backend.notification.PushService;
import com.moin.backend.period.Period;
import com.moin.backend.period.PeriodRepository;
import com.moin.backend.period.PeriodService;
import com.moin.backend.user.User;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/groups/{groupId}")
@RequiredArgsConstructor
public class CheckInController {

	private final CheckInRepository checkIns;
	private final VideoStorage storage;
	private final GroupService groupService;
	private final PeriodService periodService;
	private final PeriodRepository periods;
	private final PushService push;

	/** Complete 화면용, allComplete 면 "마지막 1명이었어요, 전원 완료!", streak 은 마감 전 값이라 화면에서 +1 */
	public record CheckInResult(Long id, String videoUrl, LocalDate logicalDate, boolean allComplete,
			PeriodService.Streak streak, GroupCard group) {}

	/**
	 * 3초 영상 업로드 = 오늘 인증, 하루 1번
	 * 검증 뒤 파일 저장, 그다음 row insert 라 insert 가 실패하면 고아 파일이 남을 수 있음 (허용)
	 */
	@PostMapping(value = "/check-ins", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	public CheckInResult create(@RequestAttribute("userId") Long userId, @PathVariable("groupId") Long groupId,
			@RequestPart("video") MultipartFile video) {
		Group g = groupService.get(groupId);
		GroupMember me = groupService.membership(groupId, userId);
		LocalDate periodStart = periodService.currentPeriodStart(g);
		if (me.getActiveFrom().isAfter(periodStart)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "다음 기간부터 인증할 수 있어요");
		}
		LocalDate today = periodService.today(g);
		if (checkIns.existsByGroupIdAndUserIdAndLogicalDate(groupId, userId, today)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "오늘은 이미 인증했어요");
		}
		CheckIn saved = checkIns.save(new CheckIn(groupId, userId, today, storage.save(video), periodService.now()));
		GroupCard card = groupService.card(g, userId);
		boolean allComplete = card.state() == GroupService.State.COMPLETE;
		notifyOthers(g, card, userId, allComplete);
		return new CheckInResult(saved.getId(), saved.getVideoUrl(), today, allComplete, periodService.streak(g), card);
	}

	/** 전원 완료면 축하를, 아니면 "누가 인증했어요" 를 나 빼고 활동 멤버에게 */
	private void notifyOthers(Group g, GroupCard card, Long actor, boolean allComplete) {
		List<Long> others = card.members().stream().map(MemberStatus::userId).filter(id -> !id.equals(actor)).toList();
		String me = card.members().stream().filter(m -> m.userId().equals(actor)).map(MemberStatus::nickname).findFirst().orElse("멤버");
		if (allComplete) {
			push.send(g, PushService.Kind.ALL_COMPLETE, others, g.getName(), "오늘 전원 완료, 스트릭 " + (card.streak() + 1) + "일");
		} else {
			push.send(g, PushService.Kind.SOCIAL, others, g.getName(), me + "님이 인증했어요");
		}
	}

	/** Feed 화면 한 페이지, 영상 있는 멤버가 앞에 */
	public record FeedMember(Long userId, String nickname, String avatarUrl, Long checkInId, String videoUrl, Instant createdAt) {}
	public record Feed(LocalDate date, int completedCount, int activeCount, List<FeedMember> members) {}

	/**
	 * 그 날짜(논리 날짜)의 멤버별 영상, 날짜 스와이프는 date 를 바꿔 다시 호출
	 * activeCount 는 그 날짜가 속한 기간의 활동 멤버 수라 과거 날짜는 당시 인원 기준
	 */
	@GetMapping("/check-ins")
	public Feed feed(@RequestAttribute("userId") Long userId, @PathVariable("groupId") Long groupId,
			@RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		Group g = groupService.get(groupId);
		groupService.membership(groupId, userId);
		LocalDate day = date == null ? periodService.today(g) : date;
		var active = periodService.activeMembers(g, periodService.periodStart(g, day));
		Map<Long, CheckIn> byUser = checkIns.findByGroupIdAndLogicalDateBetweenOrderByCreatedAtAsc(groupId, day, day)
				.stream().collect(toMap(CheckIn::getUserId, identity()));
		Map<Long, User> userById = groupService.usersOf(active);
		List<FeedMember> members = active.stream().map(m -> {
			User u = userById.get(m.getUserId());
			CheckIn c = byUser.get(m.getUserId());
			return new FeedMember(u.getId(), u.getNickname(), u.getAvatarUrl(),
					c == null ? null : c.getId(), c == null ? null : c.getVideoUrl(), c == null ? null : c.getCreatedAt());
		}).sorted(Comparator.comparing((FeedMember f) -> f.videoUrl() == null).thenComparing(FeedMember::userId)).toList();
		return new Feed(day, byUser.size(), active.size(), members);
	}

	/** 기록 화면, WEEKLY 는 7일 구간 하나로 오고 셀 7개에 뿌리는 건 클라이언트 */
	public record CalendarPeriod(LocalDate start, LocalDate end, Period.Status status) {}
	public record CalendarView(String month, LocalDate today, List<CalendarPeriod> periods,
			long totalCheckIns, int longestStreak, int perfectRate) {}

	/** 통계 정의는 BACKEND_DESIGN.md §4, totalCheckIns 는 그 달 그룹 전체 인증 수, perfectRate 는 그 달 마감 기간 중 PERFECT 비율 */
	@GetMapping("/calendar")
	public CalendarView calendar(@RequestAttribute("userId") Long userId, @PathVariable("groupId") Long groupId,
			@RequestParam(value = "month", required = false) String month) {
		Group g = groupService.get(groupId);
		groupService.membership(groupId, userId);
		LocalDate today = periodService.today(g);
		YearMonth ym;
		try {
			ym = month == null ? YearMonth.from(today) : YearMonth.parse(month);
		} catch (DateTimeParseException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "월은 2026-09 형식이에요");
		}
		LocalDate from = ym.atDay(1), to = ym.plusMonths(1).atDay(1);
		List<Period> closed = periods.findByGroupIdAndPeriodEndAfterAndPeriodStartBefore(groupId, from, to);
		long perfect = closed.stream().filter(p -> p.getStatus() == Period.Status.PERFECT).count();
		List<CalendarPeriod> view = closed.stream()
				.sorted(Comparator.comparing(Period::getPeriodStart))
				.map(p -> new CalendarPeriod(p.getPeriodStart(), p.getPeriodEnd(), p.getStatus()))
				.toList();
		return new CalendarView(ym.toString(), today, view,
				checkIns.countByGroupIdAndLogicalDateBetween(groupId, from, to.minusDays(1)),
				periodService.streak(g).longest(),
				closed.isEmpty() ? 0 : (int) Math.round(100.0 * perfect / closed.size()));
	}
}
