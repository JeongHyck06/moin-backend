package com.moin.backend.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
	Optional<User> findByExternalId(String externalId);
	@org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id")
	Optional<User> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
