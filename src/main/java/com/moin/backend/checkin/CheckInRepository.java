package com.moin.backend.checkin;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckInRepository extends JpaRepository<CheckIn, Long> {
	List<CheckIn> findByGroupIdAndLogicalDateBetweenOrderByCreatedAtAsc(Long groupId, LocalDate from, LocalDate toInclusive);
	long countByUserId(Long userId);
	boolean existsByGroupIdAndUserIdAndLogicalDate(Long groupId, Long userId, LocalDate logicalDate);
}
