package com.moin.backend.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {
	List<Period> findByGroupIdOrderByPeriodStartAsc(Long groupId);
	Optional<Period> findTopByGroupIdOrderByPeriodStartDesc(Long groupId);
	Optional<Period> findByGroupIdAndPeriodStart(Long groupId, LocalDate periodStart);
	/** [from, to) 구간과 겹치는 기간, WEEKLY 가 월 경계를 넘는 경우까지 포함 */
	List<Period> findByGroupIdAndPeriodEndAfterAndPeriodStartBefore(Long groupId, LocalDate from, LocalDate to);
}
