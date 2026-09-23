package com.moin.backend.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 로그인 제공자와 고유 ID 조합으로 계정 구분, 이메일 기준 자동 병합 금지 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

	@Id
	@GeneratedValue
	private Long id;

	/** kakao:, google:, apple: 또는 개발 전용 dev: 접두어 */
	@Column(nullable = false, unique = true)
	private String externalId;

	@Column(nullable = false, length = 20)
	private String nickname;

	private String avatarUrl;

	@Embedded
	private NotificationSettings notifications = new NotificationSettings();

	@Column(nullable = false)
	private Instant createdAt = Instant.now();

	public User(String externalId, String nickname, String avatarUrl) {
		this.externalId = externalId;
		this.nickname = nickname;
		this.avatarUrl = avatarUrl;
	}

	/** 알림 설정 화면의 알림 종류 3개 + 막차 · 전원 완료 토글 */
	@Embeddable
	@Getter
	@Setter
	@NoArgsConstructor
	public static class NotificationSettings {
		private boolean reminder = true;
		private boolean social = true;
		private boolean crisis = true;
		private boolean lastCall = true;
		private boolean allComplete = true;
	}
}
