package com.moin.backend.group;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.moin.backend.period.PeriodService;

import lombok.RequiredArgsConstructor;

/** 기존 대기 멤버만 현재 기간으로 전환, 이미 지난 기간의 집계는 유지 */
@Component
@RequiredArgsConstructor
public class PendingMembershipMigration implements ApplicationRunner {

	private final GroupRepository groups;
	private final GroupMemberRepository members;
	private final PeriodService periods;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		groups.findAll().forEach(group -> {
			var current = periods.currentPeriodStart(group);
			members.findByGroupId(group.getId()).stream()
					.filter(member -> member.getActiveFrom().isAfter(current))
					.forEach(member -> member.setActiveFrom(current));
		});
	}
}
