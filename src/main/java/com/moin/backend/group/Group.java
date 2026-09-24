package com.moin.backend.group;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 인증 그룹, 규칙 컬럼(frequency, resetTime, allowedAbsences, streakFreeze) 의미는 BACKEND_DESIGN.md §2 */
@Entity
@Table(name = "groups")
@Getter
@Setter
@NoArgsConstructor
public class Group {

	public enum Frequency { DAILY, WEEKLY }

	@Id
	@GeneratedValue
	private Long id;

	@Column(nullable = false, length = 20)
	private String name;

	/** 생성 후 변경 불가, 기간 경계가 바뀌면 기존 periods 가 무의미해짐 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Frequency frequency;

	/** WEEKLY 일 때만, 일주일에 몇 번 인증해야 완료인지 */
	private Integer weeklyTarget;

	/** 이 시각에 새 하루가 시작, 이전 인증은 전날 것으로 취급 */
	@Column(nullable = false)
	private LocalTime resetTime;

	@Column(nullable = false)
	private LocalTime reminderTime;

	/** 이 인원까지 빠져도 그 기간은 PASS */
	@Column(nullable = false)
	private int allowedAbsences;

	/** 멤버가 날짜를 골라 프리즈로 본인 인증 1회 보충 */
	@Column(nullable = false)
	private boolean streakFreeze;

	@Column(nullable = false, unique = true, length = 6)
	private String inviteCode;

	@Column(nullable = false)
	private Long ownerId;

	/** 첫 기간 시작일, 마감 스케줄러가 여기서부터 빠진 기간을 채움 */
	@Column(nullable = false)
	private LocalDate firstPeriodStart;

	@Column(nullable = false)
	private Instant createdAt;

	/** 리마인더를 보낸 논리 날짜, 하루 1번만 보내기 위한 기록 */
	private LocalDate reminderSentOn;

	/** 막차(위기) 알림을 보낸 기간 시작일, 기간당 1번 */
	private LocalDate lastCallSentOn;

	/** 기간 안에서 멤버 한 명이 채워야 하는 인증 횟수 */
	public int target() {
		return frequency == Frequency.DAILY ? 1 : weeklyTarget;
	}
}
