package com.moin.backend.group;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface GroupRepository extends JpaRepository<Group, Long> {
	boolean existsByInviteCode(String inviteCode);
	Optional<Group> findByInviteCode(String inviteCode);
	/** 인증·프리즈·기간 마감의 동시 갱신을 같은 그룹 단위로 직렬화 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from Group g where g.id = :id")
	Optional<Group> lockById(@Param("id") Long id);
}
