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

	/** 생성 후 변경 불가. 기간 경계가 바뀌면 기존 periods 가 무의미해진다 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Frequency frequency;

	/** WEEKLY 일 때만. 일주일에 몇 번 인증해야 완료인지 */
	private Integer weeklyTarget;

	/** 이 시각에 새 하루가 시작. 이전 인증은 전날로 친다 */
	@Column(nullable = false)
	private LocalTime resetTime;

	@Column(nullable = false)
	private LocalTime reminderTime;

	/** 이 인원까지 빠져도 그 기간은 PASS */
	@Column(nullable = false)
	private int allowedAbsences;

	/** 월 1회, 실패한 기간을 FROZEN 으로 바꿔 스트릭을 지킨다 */
	@Column(nullable = false)
	private boolean streakFreeze;

	@Column(nullable = false, unique = true, length = 6)
	private String inviteCode;

	@Column(nullable = false)
	private Long ownerId;

	/** 첫 기간 시작일. 마감 스케줄러가 여기서부터 빠진 기간을 채운다 */
	@Column(nullable = false)
	private LocalDate firstPeriodStart;

	@Column(nullable = false)
	private Instant createdAt;

	/** 기간 안에서 멤버 한 명이 채워야 하는 인증 횟수 */
	public int target() {
		return frequency == Frequency.DAILY ? 1 : weeklyTarget;
	}
}
