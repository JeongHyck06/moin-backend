package com.moin.backend.period;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 마감된 기간 하나의 결과, 스트릭 · 달력 · 달성률은 전부 여기서 파생 */
@Entity
@Table(name = "periods", uniqueConstraints = @UniqueConstraint(columnNames = { "groupId", "periodStart" }))
@Getter
@NoArgsConstructor
public class Period {

	/** Figma StatusIcon 4종: flame / check-circle / ice / empty circle */
	public enum Status { PERFECT, PASS, FROZEN, FAILED }

	@Id
	@GeneratedValue
	private Long id;

	@Column(nullable = false)
	private Long groupId;

	@Column(nullable = false)
	private LocalDate periodStart;

	/** 배타적 종료일, DAILY 는 start+1, WEEKLY 는 start+7 */
	@Column(nullable = false)
	private LocalDate periodEnd;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	/** 과거 날짜 프리즈 사용 후 해당 기간 결과 재집계 */
	public void updateStatus(Status status) { this.status = status; }

	public Period(Long groupId, LocalDate periodStart, LocalDate periodEnd, Status status) {
		this.groupId = groupId;
		this.periodStart = periodStart;
		this.periodEnd = periodEnd;
		this.status = status;
	}
}
