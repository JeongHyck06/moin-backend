package com.moin.backend.checkin;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckInRepository extends JpaRepository<CheckIn, Long> {
	List<CheckIn> findByGroupIdAndLogicalDateBetweenOrderByCreatedAtAsc(Long groupId, LocalDate from, LocalDate toInclusive);
	long countByUserId(Long userId);
	List<CheckIn> findByGroupIdAndUserId(Long groupId, Long userId);
	long countByGroupIdAndLogicalDateBetween(Long groupId, LocalDate from, LocalDate toInclusive);
	boolean existsByGroupIdAndUserIdAndLogicalDate(Long groupId, Long userId, LocalDate logicalDate);
	boolean existsByGroupIdAndUserIdAndFreezeMonth(Long groupId, Long userId, LocalDate freezeMonth);
	List<CheckIn> findByUserId(Long userId);
	void deleteByUserId(Long userId);
}
