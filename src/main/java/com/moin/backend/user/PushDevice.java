package com.moin.backend.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * FCM 기기 토큰, 사용자 1명이 기기 여러 대 가능해서 users 컬럼이 아니라 별도 테이블
 * 토큰이 PK 라 다른 계정으로 다시 로그인한 기기는 save 한 번으로 주인이 바뀜
 */
@Entity
@Table(name = "push_devices")
@Getter
@NoArgsConstructor
public class PushDevice {

	@Id
	@Column(length = 4096)
	private String token;

	@Column(nullable = false)
	private Long userId;

	/** ios | android */
	@Column(nullable = false, length = 10)
	private String platform;

	@Column(nullable = false)
	private Instant updatedAt;

	public PushDevice(String token, Long userId, String platform, Instant updatedAt) {
		this.token = token;
		this.userId = userId;
		this.platform = platform;
		this.updatedAt = updatedAt;
	}
}
