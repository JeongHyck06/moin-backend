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

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moin.backend.checkin.CheckIn;
import com.moin.backend.checkin.CheckInRepository;
import com.moin.backend.group.Group;
import com.moin.backend.group.GroupMember;
import com.moin.backend.group.GroupMemberRepository;
import com.moin.backend.group.GroupRepository;

import lombok.RequiredArgsConstructor;

/** 논리 날짜 · 기간 경계 · 완료 판정 · 스트릭, 시간은 항상 주입된 Clock 기준 */
@Service
@RequiredArgsConstructor
public class PeriodService {

	private final GroupRepository groups;
	private final GroupMemberRepository members;
	private final CheckInRepository checkIns;
	private final PeriodRepository periods;
	private final Clock clock;
	private final ZoneId zone;

	/** perfect + pass + frozen = current, 화면의 "완벽 10 · 프리즈 1 · 결석허용 1" */
	public record Streak(int current, int perfect, int pass, int frozen, int longest) {}

	public Instant now() {
		return clock.instant();
	}

	/** 리셋 시각 이전은 전날로 취급, "새벽 3:59 인증도 전날 인증으로 인정돼요" */
	public LocalDate logicalDate(Instant at, LocalTime resetTime) {
		LocalDateTime local = LocalDateTime.ofInstant(at, zone);
		return local.toLocalTime().isBefore(resetTime) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
	}

	public LocalDate today(Group g) {
		return logicalDate(now(), g.getResetTime());
	}

	/** 논리 날짜가 속한 기간의 시작일, WEEKLY 는 그 주 월요일 */
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

	/** 지금 열려 있는 기간의 시작일, 카드·상세·인증 저장 전부 이 값이 기준 */
	public LocalDate currentPeriodStart(Group g) {
		return periodStart(g, today(g));
	}

	/** 그 기간에 집계되는 멤버 (activeFrom <= 기간 시작) */
	public List<GroupMember> activeMembers(Group g, LocalDate start) {
		return members.findByGroupIdAndActiveFromLessThanEqual(g.getId(), start);
	}

	/** 기간 안의 인증을 멤버별로 묶음, 생성 순 정렬이라 마지막 원소가 최신 영상 */
	public Map<Long, List<CheckIn>> checkInsByUser(Group g, LocalDate start) {
		return checkIns.findByGroupIdAndLogicalDateBetweenOrderByCreatedAtAsc(g.getId(), start, periodEnd(g, start).minusDays(1))
				.stream().collect(groupingBy(CheckIn::getUserId));
	}

	/** 순수 함수, 미완료 인원으로 기간 상태 결정 */
	public static Period.Status status(Group g, long missing, boolean freezeUsed) {
		if (missing > g.getAllowedAbsences()) return Period.Status.FAILED;
		if (freezeUsed) return Period.Status.FROZEN;
		if (missing == 0) return Period.Status.PERFECT;
		return Period.Status.PASS;
	}

	/** 프리즈도 본인 인증 1회로 집계, 다른 멤버의 미인증까지 대신 채우지는 않음 */
	private Period.Status result(Group g, LocalDate start) {
		Map<Long, List<CheckIn>> done = checkInsByUser(g, start);
		List<GroupMember> active = activeMembers(g, start);
		long missing = active.stream().filter(m -> done.getOrDefault(m.getUserId(), List.of()).size() < g.target()).count();
		boolean frozen = active.stream().flatMap(m -> done.getOrDefault(m.getUserId(), List.of()).stream()).anyMatch(CheckIn::isFrozen);
		return status(g, missing, frozen);
	}

	/** 마감된 날짜의 인증 보충 시 저장 결과도 갱신, 기존 자동 프리즈 기록은 소급 취소하지 않음 */
	public void refreshClosedPeriod(Group g, LocalDate date) {
		periods.findByGroupIdAndPeriodStart(g.getId(), periodStart(g, date)).ifPresent(p -> {
			Period.Status updated = result(g, p.getPeriodStart());
			if (p.getStatus() != Period.Status.FROZEN || updated != Period.Status.FAILED) p.updateStatus(updated);
		});
	}

	/** 순수 함수, 오름차순 기간 목록에서 스트릭 계산, 열려 있는 오늘 기간은 제외 */
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

	/** ponytail: 매번 전체 그룹 순회, 그룹이 수천 개면 resetTime 기준으로 대상만 조회 */
	@Scheduled(fixedDelayString = "${moin.close-interval-ms}")
	@Transactional
	public void closeAllDuePeriods() {
		groups.findAll().stream().sorted(java.util.Comparator.comparing(Group::getId)).forEach(this::closeDuePeriods);
	}

	/**
	 * 마감 시각이 지난 기간을 순서대로 닫아 periods 에 기록, 서버가 며칠 꺼져 있었어도 빠진 기간을 전부 채움
	 * 과거 프리즈 사용은 refreshClosedPeriod 에서 해당 기간만 다시 계산
	 */
	@Transactional
	public void closeDuePeriods(Group g) {
		if (groups.lockById(g.getId()).isEmpty()) return;
		LocalDate current = currentPeriodStart(g);
		LocalDate next = periods.findTopByGroupIdOrderByPeriodStartDesc(g.getId())
				.map(Period::getPeriodEnd)
				.orElse(g.getFirstPeriodStart());
		while (next.isBefore(current)) {
			periods.save(new Period(g.getId(), next, periodEnd(g, next), result(g, next)));
			next = periodEnd(g, next);
		}
	}
}
