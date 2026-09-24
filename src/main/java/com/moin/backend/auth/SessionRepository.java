package com.moin.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<Session, String> {
	void deleteByUserId(Long userId);
}
