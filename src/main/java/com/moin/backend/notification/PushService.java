package com.moin.backend.notification;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.moin.backend.group.Group;
import com.moin.backend.group.GroupMember;
import com.moin.backend.group.GroupMemberRepository;
import com.moin.backend.user.PushDevice;
import com.moin.backend.user.PushDeviceRepository;
import com.moin.backend.user.User;
import com.moin.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 누구에게 보낼지 결정, 알림 종류 토글(User.NotificationSettings) 과 그룹 음소거(GroupMember.muted) 를 둘 다 통과한 기기만
 * 알림 탭 시 그룹 상세로 가도록 data 에 groupId 를 항상 넣음
 */
@Service
@RequiredArgsConstructor
public class PushService {

	/** 알림 설정 화면의 토글 5개와 1:1 */
	public enum Kind { REMINDER, LAST_CALL, ALL_COMPLETE, SOCIAL, CRISIS }

	private final GroupMemberRepository members;
	private final UserRepository users;
	private final PushDeviceRepository devices;
	private final PushSender sender;

	/** 그룹 안의 특정 사용자들에게, 대상 목록은 호출자가 이미 상황(미인증 등)으로 골라 둔 상태 */
	public void send(Group g, Kind kind, Collection<Long> userIds, String title, String body) {
		if (userIds.isEmpty()) return;
		Set<Long> muted = members.findByGroupId(g.getId()).stream()
				.filter(GroupMember::isMuted).map(GroupMember::getUserId).collect(Collectors.toSet());
		List<Long> targets = users.findAllById(userIds).stream()
				.filter(u -> !muted.contains(u.getId()) && wants(u, kind))
				.map(User::getId).toList();
		List<String> tokens = devices.findByUserIdIn(targets).stream().map(PushDevice::getToken).toList();
		sender.send(tokens, title, body, Map.of("groupId", String.valueOf(g.getId()), "kind", kind.name()));
	}

	private static boolean wants(User u, Kind kind) {
		User.NotificationSettings s = u.getNotifications();
		return switch (kind) {
			case REMINDER -> s.isReminder();
			case LAST_CALL -> s.isLastCall();
			case ALL_COMPLETE -> s.isAllComplete();
			case SOCIAL -> s.isSocial();
			case CRISIS -> s.isCrisis();
		};
	}
}
