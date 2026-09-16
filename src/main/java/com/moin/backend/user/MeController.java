package com.moin.backend.user;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moin.backend.checkin.CheckInRepository;
import com.moin.backend.group.GroupMember;
import com.moin.backend.group.GroupMemberRepository;
import com.moin.backend.group.GroupRepository;
import com.moin.backend.period.PeriodService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {

	private final UserRepository users;
	private final GroupRepository groups;
	private final GroupMemberRepository members;
	private final CheckInRepository checkIns;
	private final PeriodService periodService;

	/** 마이페이지 프로필. totalStreak = 내 그룹 현재 스트릭 합, totalCheckIns = 내 인증 전체 수 */
	public record Profile(Long id, String nickname, String avatarUrl, int totalStreak, long totalCheckIns) {}

	@GetMapping
	public Profile me(@RequestAttribute("userId") Long userId) {
		User u = users.findById(userId).orElseThrow();
		List<Long> groupIds = members.findByUserId(userId).stream().map(GroupMember::getGroupId).toList();
		int totalStreak = groups.findAllById(groupIds).stream().mapToInt(g -> periodService.streak(g).current()).sum();
		return new Profile(u.getId(), u.getNickname(), u.getAvatarUrl(), totalStreak, checkIns.countByUserId(userId));
	}
}
