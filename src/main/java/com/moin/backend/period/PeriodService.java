package com.moin.backend.period;

import static java.util.stream.Collectors.groupingBy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.moin.backend.checkin.CheckIn;
import com.moin.backend.checkin.CheckInRepository;
import com.moin.backend.group.Group;
import com.moin.backend.group.GroupMember;
import com.moin.backend.group.GroupMemberRepository;

import lombok.RequiredArgsConstructor;

/** 논리 날짜 · 기간 경계 · 완료 판정 · 스트릭. 시간은 항상 주입된 Clock 으로 */
@Service
@RequiredArgsConstructor
public class PeriodService {

	private final GroupMemberRepository members;
	private final CheckInRepository checkIns;
	private final PeriodRepository periods;
	private final Clock clock;
	private final ZoneId zone;

	/** current 안의 perfect/pass/frozen 합 = current. "완벽 10 · 프리즈 1 · 결석허용 1" */
	public record Streak(int current, int perfect, int pass, int frozen, int longest) {}

	public Instant now() {
		return clock.instant();
	}

	/** 리셋 시각 이전은 전날로 친다. "새벽 3:59 인증도 전날 인증으로 인정돼요" */
	public LocalDate logicalDate(Instant at, LocalTime resetTime) {
		LocalDateTime local = LocalDateTime.ofInstant(at, zone);
		return local.toLocalTime().isBefore(resetTime) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
	}

	public LocalDate today(Group g) {
		return logicalDate(now(), g.getResetTime());
	}

	/** 논리 날짜가 속한 기간의 시작일. WEEKLY 는 그 주 월요일 */
	public LocalDate periodStart(Group g, LocalDate logicalDate) {
		return g.getFrequency() == Group.Frequency.DAILY
				? logicalDate
				: logicalDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	/** 배타적 종료일 */
	public LocalDate periodEnd(Group g, LocalDate start) {
		return g.getFrequency() == Group.Frequency.DAILY ? start.plusDays(1) : start.plusWeeks(1);
	}

	/** 종료일의 리셋 시각 = 마감 */
	public Instant deadline(Group g, LocalDate start) {
		return periodEnd(g, start).atTime(g.getResetTime()).atZone(zone).toInstant();
	}

	/** 지금 열려 있는 기간의 시작일. 카드·상세·인증 저장이 전부 이 값을 기준으로 본다 */
	public LocalDate currentPeriodStart(Group g) {
		return periodStart(g, today(g));
	}

	/** 그 기간에 집계되는 멤버 (activeFrom <= 기간 시작) */
	public List<GroupMember> activeMembers(Group g, LocalDate start) {
		return members.findByGroupIdAndActiveFromLessThanEqual(g.getId(), start);
	}

	/** 기간 안의 인증을 멤버별로. 생성 순 정렬이라 마지막 원소가 최신 영상 */
	public Map<Long, List<CheckIn>> checkInsByUser(Group g, LocalDate start) {
		return checkIns.findByGroupIdAndLogicalDateBetweenOrderByCreatedAtAsc(g.getId(), start, periodEnd(g, start).minusDays(1))
				.stream().collect(groupingBy(CheckIn::getUserId));
	}

	/** 순수 함수. 미완료 인원 → 기간 상태 */
	public static Period.Status status(Group g, long missing, boolean freezeAvailable) {
		if (missing == 0) return Period.Status.PERFECT;
		if (missing <= g.getAllowedAbsences()) return Period.Status.PASS;
		if (g.isStreakFreeze() && freezeAvailable) return Period.Status.FROZEN;
		return Period.Status.FAILED;
	}

	/** 순수 함수. 오름차순 기간 목록 → 스트릭. 열려 있는 오늘 기간은 포함하지 않는다 */
	public static Streak streak(List<Period> asc) {
		int longest = 0, run = 0;
		for (Period p : asc) {
			run = p.getStatus() == Period.Status.FAILED ? 0 : run + 1;
			longest = Math.max(longest, run);
		}
		int current = 0, perfect = 0, pass = 0, frozen = 0;
		for (int i = asc.size() - 1; i >= 0 && asc.get(i).getStatus() != Period.Status.FAILED; i--) {
			current++;
			switch (asc.get(i).getStatus()) {
				case PERFECT -> perfect++;
				case PASS -> pass++;
				case FROZEN -> frozen++;
				default -> { }
			}
		}
		return new Streak(current, perfect, pass, frozen, longest);
	}

	public Streak streak(Group g) {
		return streak(periods.findByGroupIdOrderByPeriodStartAsc(g.getId()));
	}
}
