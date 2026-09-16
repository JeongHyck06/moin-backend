package com.moin.backend.period;

import static com.moin.backend.period.Period.Status.FAILED;
import static com.moin.backend.period.Period.Status.FROZEN;
import static com.moin.backend.period.Period.Status.PASS;
import static com.moin.backend.period.Period.Status.PERFECT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.moin.backend.group.Group;
import com.moin.backend.period.PeriodService.Streak;

/** 순수 함수만, Spring 없이 실행, 기준 시각 2026-09-16(수) 10:00 KST */
class PeriodServiceTest {

	static final Instant NOW = Instant.parse("2026-09-16T01:00:00Z");
	final PeriodService svc = new PeriodService(null, null, null, null, Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("Asia/Seoul"));

	static Group group(Group.Frequency f, int allowedAbsences, boolean freeze) {
		Group g = new Group();
		g.setFrequency(f);
		g.setWeeklyTarget(3);
		g.setResetTime(LocalTime.of(4, 0));
		g.setAllowedAbsences(allowedAbsences);
		g.setStreakFreeze(freeze);
		return g;
	}

	static Period period(Period.Status s) {
		return new Period(1L, LocalDate.EPOCH, LocalDate.EPOCH.plusDays(1), s);
	}

	@Test
	void 리셋_시각_전은_전날로_친다() {
		LocalTime reset = LocalTime.of(4, 0);
		assertEquals(LocalDate.of(2026, 9, 15), svc.logicalDate(Instant.parse("2026-09-15T18:59:00Z"), reset)); // 03:59 KST
		assertEquals(LocalDate.of(2026, 9, 16), svc.logicalDate(Instant.parse("2026-09-15T19:00:00Z"), reset)); // 04:00 KST
	}

	@Test
	void 매일_기간은_하루_마감은_다음날_리셋_시각() {
		Group g = group(Group.Frequency.DAILY, 1, false);
		assertEquals(LocalDate.of(2026, 9, 16), svc.currentPeriodStart(g));
		assertEquals(LocalDate.of(2026, 9, 17), svc.periodEnd(g, LocalDate.of(2026, 9, 16)));
		assertEquals(Instant.parse("2026-09-16T19:00:00Z"), svc.deadline(g, LocalDate.of(2026, 9, 16))); // 9/17 04:00 KST
		assertEquals(1, g.target());
	}

	@Test
	void 주간_기간은_월요일에_시작한다() {
		Group g = group(Group.Frequency.WEEKLY, 1, false);
		assertEquals(LocalDate.of(2026, 9, 14), svc.currentPeriodStart(g));
		assertEquals(LocalDate.of(2026, 9, 21), svc.periodEnd(g, LocalDate.of(2026, 9, 14)));
		assertEquals(Instant.parse("2026-09-20T19:00:00Z"), svc.deadline(g, LocalDate.of(2026, 9, 14))); // 9/21 04:00 KST
		assertEquals(LocalDate.of(2026, 9, 14), svc.periodStart(g, LocalDate.of(2026, 9, 20))); // 일요일도 같은 주
		assertEquals(3, g.target());
	}

	@Test
	void 미완료_인원에_따른_기간_상태() {
		Group g = group(Group.Frequency.DAILY, 1, true);
		assertEquals(PERFECT, PeriodService.status(g, 0, true));
		assertEquals(PASS, PeriodService.status(g, 1, true));
		assertEquals(FROZEN, PeriodService.status(g, 2, true));
		assertEquals(FAILED, PeriodService.status(g, 2, false)); // 이번 달 프리즈 이미 사용
		g.setStreakFreeze(false);
		assertEquals(FAILED, PeriodService.status(g, 2, true));
	}

	@Test
	void 스트릭은_최근부터_FAILED_전까지_센다() {
		List<Period> ps = Stream.of(PERFECT, FAILED, PERFECT, PASS, FROZEN).map(PeriodServiceTest::period).toList();
		assertEquals(new Streak(3, 1, 1, 1, 3), PeriodService.streak(ps));
		assertEquals(new Streak(0, 0, 0, 0, 2), PeriodService.streak(List.of(period(PERFECT), period(PASS), period(FAILED))));
		assertEquals(new Streak(0, 0, 0, 0, 0), PeriodService.streak(List.of()));
	}
}
