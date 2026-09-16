package com.moin.backend.group;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {
	List<GroupMember> findByUserId(Long userId);
	List<GroupMember> findByGroupId(Long groupId);
	Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);
	List<GroupMember> findByGroupIdAndActiveFromLessThanEqual(Long groupId, LocalDate periodStart);
}
