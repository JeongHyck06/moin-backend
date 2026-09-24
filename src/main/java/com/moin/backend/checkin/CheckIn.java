package com.moin.backend.checkin;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 3초 영상 인증 1건, 하루(논리 날짜) 최대 1번 */
@Entity
@Table(name = "check_ins", uniqueConstraints = {
		@UniqueConstraint(columnNames = { "groupId", "userId", "logicalDate" }),
		@UniqueConstraint(columnNames = { "groupId", "userId", "freezeMonth" }) })
@Getter
@NoArgsConstructor
public class CheckIn {

	@Id
	@GeneratedValue
	private Long id;

	@Column(nullable = false)
	private Long groupId;

	@Column(nullable = false)
	private Long userId;

	/** 그룹 리셋 시각을 적용한 날짜, 저장 시점에 확정 */
	@Column(nullable = false)
	private LocalDate logicalDate;

	@Column(nullable = false)
	private String videoUrl;

	@Column(nullable = false)
	private Instant createdAt;

	/** 사용한 달의 첫날, 일반 영상은 null이라 기존 인증과 월별 잔여 수량 분리 */
	private LocalDate freezeMonth;

	@Column(nullable = false)
	@org.hibernate.annotations.ColumnDefault("false")
	private boolean frozen;

	public boolean isFrozen() { return frozen; }

	/** 기존 NOT NULL 영상 컬럼은 유지하고 API에는 가짜 영상 주소를 내보내지 않음 */
	public String getVideoUrl() { return isFrozen() ? null : videoUrl; }

	public static CheckIn freeze(Long groupId, Long userId, LocalDate date, LocalDate month, Instant now) {
		CheckIn c = new CheckIn(groupId, userId, date, "", now);
		c.freezeMonth = month;
		c.frozen = true;
		return c;
	}

	public CheckIn(Long groupId, Long userId, LocalDate logicalDate, String videoUrl, Instant createdAt) {
		this.groupId = groupId;
		this.userId = userId;
		this.logicalDate = logicalDate;
		this.videoUrl = videoUrl;
		this.createdAt = createdAt;
	}
}
