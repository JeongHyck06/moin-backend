package com.moin.backend.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {
	List<Period> findByGroupIdOrderByPeriodStartAsc(Long groupId);
	Optional<Period> findTopByGroupIdOrderByPeriodStartDesc(Long groupId);
	/** 프리즈는 월 1회, 그 달에 FROZEN 이 이미 있는지 */
	boolean existsByGroupIdAndStatusAndPeriodStartBetween(Long groupId, Period.Status status, LocalDate from, LocalDate to);
	/** [from, to) 구간과 겹치는 기간, WEEKLY 가 월 경계를 넘는 경우까지 포함 */
	List<Period> findByGroupIdAndPeriodEndAfterAndPeriodStartBefore(Long groupId, LocalDate from, LocalDate to);
}
