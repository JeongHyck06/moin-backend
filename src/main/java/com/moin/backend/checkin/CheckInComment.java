package com.moin.backend.checkin;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 인증 1건에 속하는 댓글, 작성자와 작성 시각은 서버에서 확정 */
@Entity
@Table(name = "check_in_comments", indexes = @Index(name = "idx_comment_check_in_id", columnList = "checkInId,id"))
@Getter
@NoArgsConstructor
public class CheckInComment {

	@Id @GeneratedValue
	private Long id;

	@Column(nullable = false)
	private Long checkInId;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false, length = 500)
	private String body;

	@Column(nullable = false)
	private Instant createdAt;

	public CheckInComment(Long checkInId, Long userId, String body, Instant createdAt) {
		this.checkInId = checkInId;
		this.userId = userId;
		this.body = body;
		this.createdAt = createdAt;
	}
}
