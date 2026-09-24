package com.moin.backend.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.moin.backend.checkin.CheckIn;
import com.moin.backend.group.Group;
import com.moin.backend.group.GroupMember;
import com.moin.backend.group.GroupRepository;
import com.moin.backend.period.PeriodService;

import lombok.RequiredArgsConstructor;

/**
 * 리마인더(reminderTime) 와 막차(마감 crisisHours 전) 를 그룹마다 하루·기간당 1번
 * 보낸 날짜를 Group 에 기록해 두므로 틱 간격이 흔들리거나 서버가 재시작돼도 중복 발송 없음
 */
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

	private final GroupRepository groups;
	private final PeriodService periodService;
	private final PushService push;
	private final ZoneId zone;

	@Value("${moin.crisis-hours}")
	private int crisisHours;

	@Scheduled(fixedDelayString = "${moin.close-interval-ms}")
	@Transactional
	public void tick() {
		groups.findAll().stream().sorted(java.util.Comparator.comparing(Group::getId)).forEach(this::tick);
	}

	@Transactional
	public void tick(Group g) {
		if (groups.lockById(g.getId()).isEmpty()) return;
		Instant now = periodService.now();
		LocalDate today = periodService.today(g);
		LocalDate periodStart = periodService.currentPeriodStart(g);
		Map<Long, List<CheckIn>> done = periodService.checkInsByUser(g, periodStart);
		List<Long> notDone = periodService.activeMembers(g, periodStart).stream()
				.map(GroupMember::getUserId)
				.filter(id -> done.getOrDefault(id, List.of()).size() < g.target())
				.toList();
		if (notDone.isEmpty()) return;

		// 리마인더, 오늘 이미 인증한 사람은 주 N회 그룹이라도 뺀다
		boolean reminderDue = !LocalDateTime.ofInstant(now, zone).toLocalTime().isBefore(g.getReminderTime());
		if (reminderDue && !today.equals(g.getReminderSentOn())) {
			List<Long> targets = notDone.stream()
					.filter(id -> done.getOrDefault(id, List.of()).stream().noneMatch(c -> c.getLogicalDate().equals(today)))
					.toList();
			push.send(g, PushService.Kind.REMINDER, targets, g.getName(), "아직 인증 안 했어요, 3초면 끝나요");
			g.setReminderSentOn(today);
			groups.save(g);
		}

		// 막차, 결석 허용을 넘겨 스트릭이 끊길 상황이면 위기 알림으로
		Instant deadline = periodService.deadline(g, periodStart);
		boolean lastCallDue = !now.plus(Duration.ofHours(crisisHours)).isBefore(deadline);
		if (lastCallDue && !periodStart.equals(g.getLastCallSentOn())) {
			boolean failing = notDone.size() > g.getAllowedAbsences();
			push.send(g, failing ? PushService.Kind.CRISIS : PushService.Kind.LAST_CALL, notDone, g.getName(),
					failing ? "마감 " + crisisHours + "시간 전, 지금 안 하면 스트릭이 끊겨요" : "마감 " + crisisHours + "시간 전, " + notDone.size() + "명 남았어요");
			g.setLastCallSentOn(periodStart);
			groups.save(g);
		}
	}
}
