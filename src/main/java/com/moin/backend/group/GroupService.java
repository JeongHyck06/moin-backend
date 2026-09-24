package com.moin.backend.group;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonFormat;

import com.moin.backend.checkin.CheckIn;
import com.moin.backend.period.Period;
import com.moin.backend.period.PeriodRepository;
import com.moin.backend.period.PeriodService;
import com.moin.backend.user.User;
import com.moin.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/** 홈 카드 · 그룹 상세 응답 조립 */
@Service
@RequiredArgsConstructor
public class GroupService {

	private final GroupRepository groups;
	private final GroupMemberRepository members;
	private final UserRepository users;
	private final PeriodRepository periods;
	private final PeriodService periodService;

	@Value("${moin.crisis-hours}")
	private int crisisHours;

	/** 선언 순서가 홈 정렬 순서 (Figma GroupCard 설명) */
	public enum State { NEEDS_ME, WAITING_OTHERS, COMPLETE, CRISIS }

	public record MemberStatus(Long userId, String nickname, String avatarUrl, boolean done, int doneCount, String videoUrl, Long checkInId) {}

	public record GroupCard(Long id, String name, Group.Frequency frequency, Integer weeklyTarget,
			@JsonFormat(pattern = "HH:mm") LocalTime resetTime,
			State state, int streak, int activeCount, int completedCount, int allowedAbsences,
			boolean myDone, int myDoneCount, boolean joinsNextPeriod,
			LocalDate periodStart, Instant deadline, List<MemberStatus> members) {}

	public record GroupDetail(GroupCard card, String inviteCode, @JsonFormat(pattern = "HH:mm") LocalTime reminderTime,
			boolean streakFreeze, boolean muted, boolean isOwner,
			PeriodService.Streak streak, int threshold, int periodVideoCount,
			int monthCompletedPeriods, int monthClosedPeriods) {}

	public record InvitePreview(Long id, String name, Group.Frequency frequency, Integer weeklyTarget,
			@JsonFormat(pattern = "HH:mm") LocalTime resetTime, int memberCount, int streak,
			List<MemberStatus> members, boolean alreadyMember, LocalDate joinsFrom) {}

	public Group get(Long id) {
		return groups.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "그룹을 찾을 수 없어요"));
	}

	/** 코드는 대문자로만 생성, 입력은 소문자·공백이 섞여도 허용 */
	public Group byInviteCode(String code) {
		return groups.findByInviteCode(code.trim().toUpperCase())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "초대코드를 확인해주세요"));
	}

	/** 그룹 상세·수정은 멤버만, 다음 기간부터 참여하는 대기 멤버도 멤버 */
	public GroupMember membership(Long groupId, Long userId) {
		return members.findByGroupIdAndUserId(groupId, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "그룹 멤버만 볼 수 있어요"));
	}

	/**
	 * Join 화면 미리보기, memberCount 는 대기 멤버까지 포함한 전체, members 는 이번 기간 활동 멤버만
	 * joinsFrom 은 참여 즉시 집계되는 현재 기간의 시작일
	 */
	public InvitePreview preview(Group g, Long me) {
		GroupCard card = card(g, me);
		boolean already = members.findByGroupIdAndUserId(g.getId(), me).isPresent();
		LocalDate joinsFrom = periodService.currentPeriodStart(g);
		return new InvitePreview(g.getId(), g.getName(), g.getFrequency(), g.getWeeklyTarget(), g.getResetTime(),
				members.findByGroupId(g.getId()).size(), card.streak(), card.members(), already, joinsFrom);
	}

	/** 멤버 목록의 사용자 정보를 한 번의 IN 쿼리로 */
	public Map<Long, User> usersOf(List<GroupMember> members) {
		return users.findAllById(members.stream().map(GroupMember::getUserId).toList())
				.stream().collect(toMap(User::getId, identity()));
	}

	public List<GroupCard> home(Long me) {
		List<Long> ids = members.findByUserId(me).stream().map(GroupMember::getGroupId).toList();
		return groups.findAllById(ids).stream()
				.map(g -> card(g, me))
				.sorted(Comparator.comparing(GroupCard::state))
				.toList();
	}

	/**
	 * 홈 카드 1장, me 가 활동 멤버가 아니면(대기 멤버·비멤버) 재촉 대상이 아니라 NEEDS_ME/CRISIS 불가
	 * CRISIS 는 마감까지 crisisHours 이내이고 내가 미완료일 때만
	 */
	public GroupCard card(Group g, Long me) {
		LocalDate start = periodService.currentPeriodStart(g);
		List<GroupMember> active = periodService.activeMembers(g, start);
		Map<Long, List<CheckIn>> byUser = periodService.checkInsByUser(g, start);
		Map<Long, User> userById = usersOf(active);

		List<MemberStatus> statuses = active.stream().map(m -> {
			User u = userById.get(m.getUserId());
			List<CheckIn> cs = byUser.getOrDefault(m.getUserId(), List.of());
			String video = cs.isEmpty() ? null : cs.get(cs.size() - 1).getVideoUrl();
			return new MemberStatus(u.getId(), u.getNickname(), u.getAvatarUrl(), cs.size() >= g.target(), cs.size(), video,
					cs.isEmpty() ? null : cs.get(cs.size() - 1).getId());
		}).sorted(Comparator.comparing(MemberStatus::done).reversed().thenComparing(MemberStatus::userId)).toList();

		int completed = (int) statuses.stream().filter(MemberStatus::done).count();
		MemberStatus mine = statuses.stream().filter(s -> s.userId().equals(me)).findFirst().orElse(null);
		boolean joinsNext = mine == null;
		boolean myDone = mine != null && mine.done();
		boolean needsMe = mine != null && !myDone;
		Instant now = periodService.now();
		Instant deadline = periodService.deadline(g, start);

		State state;
		if (needsMe && !now.plus(Duration.ofHours(crisisHours)).isBefore(deadline)) state = State.CRISIS;
		else if (needsMe) state = State.NEEDS_ME;
		else if (completed == statuses.size()) state = State.COMPLETE;
		else state = State.WAITING_OTHERS;

		return new GroupCard(g.getId(), g.getName(), g.getFrequency(), g.getWeeklyTarget(), g.getResetTime(),
				state, periodService.streak(g).current(), statuses.size(), completed, g.getAllowedAbsences(),
				myDone, mine == null ? 0 : mine.doneCount(), joinsNext, start, deadline, statuses);
	}

	/**
	 * 그룹 상세, threshold = 이 인원만 인증하면 PASS ("3명이면 완료")
	 * isOwner 는 PATCH /groups/{id} 의 방장 판정과 같은 기준, 앱은 이걸로 이름 변경 Row 노출 여부를 정함
	 * monthClosed/Completed 는 이번 달과 겹치는 마감 기간 기준 (§4 통계 정의)
	 */
	public GroupDetail detail(Group g, GroupMember membership) {
		GroupCard card = card(g, membership.getUserId());
		LocalDate monthStart = periodService.today(g).withDayOfMonth(1);
		List<Period> month = periods.findByGroupIdAndPeriodEndAfterAndPeriodStartBefore(g.getId(), monthStart, monthStart.plusMonths(1));
		int completedPeriods = (int) month.stream().filter(p -> p.getStatus() != Period.Status.FAILED).count();
		int videos = card.members().stream().mapToInt(MemberStatus::doneCount).sum();
		return new GroupDetail(card, g.getInviteCode(), g.getReminderTime(), g.isStreakFreeze(), membership.isMuted(),
				g.getOwnerId().equals(membership.getUserId()),
				periodService.streak(g), card.activeCount() - g.getAllowedAbsences(), videos, completedPeriods, month.size());
	}
}
