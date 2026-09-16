package com.moin.backend.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "auth_sessions")
@Getter
@NoArgsConstructor
public class Session {

	@Id
	@Column(length = 64)
	private String token;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false)
	private Instant expiresAt;

	public Session(String token, Long userId, Instant expiresAt) {
		this.token = token;
		this.userId = userId;
		this.expiresAt = expiresAt;
	}
}
