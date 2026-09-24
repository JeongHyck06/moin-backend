package com.moin.backend.checkin;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckInCommentRepository extends JpaRepository<CheckInComment, Long> {
	List<CheckInComment> findByCheckInIdAndIdLessThanOrderByIdDesc(Long checkInId, Long before, Pageable pageable);
	void deleteByUserId(Long userId);
	void deleteByCheckInIdIn(List<Long> ids);
}
