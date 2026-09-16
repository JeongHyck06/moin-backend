package com.moin.backend.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 로그인 1회 = 1행, 만료만 있고 갱신은 없음, 만료 시 카카오 로그인부터 다시 */
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
