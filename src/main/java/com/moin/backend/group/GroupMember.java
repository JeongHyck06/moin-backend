package com.moin.backend.group;

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
import lombok.Setter;

/** 그룹 소속 1건, 방장도 한 행 존재, (group_id, user_id) 유니크 */
@Entity
@Table(name = "group_members", uniqueConstraints = @UniqueConstraint(columnNames = { "groupId", "userId" }))
@Getter
@Setter
@NoArgsConstructor
public class GroupMember {

	@Id
	@GeneratedValue
	private Long id;

	@Column(nullable = false)
	private Long groupId;

	@Column(nullable = false)
	private Long userId;

	/** 이 날(기간 시작일)부터 집계에 포함, 초대로 들어오면 다음 기간 시작일 */
	@Column(nullable = false)
	private LocalDate activeFrom;

	@Column(nullable = false)
	private boolean muted;

	@Column(nullable = false)
	private Instant joinedAt;

	public GroupMember(Long groupId, Long userId, LocalDate activeFrom, Instant joinedAt) {
		this.groupId = groupId;
		this.userId = userId;
		this.activeFrom = activeFrom;
		this.joinedAt = joinedAt;
	}
}
