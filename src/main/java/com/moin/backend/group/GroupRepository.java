package com.moin.backend.group;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupRepository extends JpaRepository<Group, Long> {
	boolean existsByInviteCode(String inviteCode);
	Optional<Group> findByInviteCode(String inviteCode);
}
