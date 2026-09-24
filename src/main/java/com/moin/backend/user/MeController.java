package com.moin.backend.user;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.moin.backend.checkin.CheckInRepository;
import com.moin.backend.auth.SessionRepository;
import com.moin.backend.group.Group;
import com.moin.backend.group.GroupMember;
import com.moin.backend.group.GroupMemberRepository;
import com.moin.backend.group.GroupRepository;
import com.moin.backend.period.Period;
import com.moin.backend.period.PeriodRepository;
import com.moin.backend.period.PeriodService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {

	private final UserRepository users;
	private final GroupRepository groups;
	private final GroupMemberRepository members;
	private final CheckInRepository checkIns;
	private final PeriodRepository periods;
	private final PeriodService periodService;
	private final PushDeviceRepository devices;
	private final SessionRepository sessions;
	private final AvatarStorage avatars;
	private final AccountDeletionService deletion;
	private final com.moin.backend.storage.FileDeletionService fileDeletion;
	public record DeleteAccount(boolean confirmed) {}

	@org.springframework.web.bind.annotation.DeleteMapping
	public AccountDeletionService.Result delete(@RequestAttribute("userId") Long userId, @RequestBody DeleteAccount body) {
		return deletion.delete(userId, body.confirmed());
	}

	public record Logout(@Size(max = 512) String pushToken) {}

	/** 현재 세션과 이 계정 소유의 기기 토큰만 제거, 다른 기기 로그인은 유지 */
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	public void logout(@RequestAttribute("userId") Long userId,
			@RequestHeader("Authorization") String authorization, @Valid @RequestBody(required = false) Logout body) {
		if (body != null && body.pushToken() != null) devices.deleteByTokenAndUserId(body.pushToken(), userId);
		sessions.deleteById(authorization.substring(7));
	}

	/** 마이페이지 프로필, totalStreak = 내 그룹 현재 스트릭 합, totalCheckIns = 내 인증 전체 수 */
	public record Profile(Long id, String nickname, String avatarUrl, int totalStreak, long totalCheckIns, String provider) {}

	/** 본인 프로필만 변경, 사진 검증 실패 시 닉네임도 함께 유지 */
	@PostMapping(value = "/profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Transactional
	public Profile updateProfile(@RequestAttribute("userId") Long userId,
			@RequestParam("nickname") String nickname, @RequestPart(value = "avatar", required = false) MultipartFile avatar) {
		String name = nickname.strip();
		if (name.isBlank() || name.length() > 20) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "닉네임은 1~20자로 입력해주세요");
		}
		User user = users.lockById(userId).orElseThrow();
		if (avatar != null) {
			String saved = avatars.save(avatar);
			fileDeletion.enqueue(user.getAvatarUrl());
			user.setAvatarUrl(saved);
		}
		user.setNickname(name);
		users.save(user);
		return me(userId);
	}

	@GetMapping
	public Profile me(@RequestAttribute("userId") Long userId) {
		User u = users.findById(userId).orElseThrow();
		List<Long> groupIds = members.findByUserId(userId).stream().map(GroupMember::getGroupId).toList();
		int totalStreak = groups.findAllById(groupIds).stream().mapToInt(g -> periodService.streak(g).current()).sum();
		return new Profile(u.getId(), u.getNickname(), u.getAvatarUrl(), totalStreak, checkIns.countByUserId(userId), u.getExternalId().split(":", 2)[0]);
	}

	/** 마이페이지 "내 그룹" 행, "달성률 92% · 12일" */
	public record MyGroup(Long id, String name, int streak, int achievementRate) {}

	@GetMapping("/groups")
	public List<MyGroup> groups(@RequestAttribute("userId") Long userId) {
		return members.findByUserId(userId).stream().map(m -> {
			Group g = groups.findById(m.getGroupId()).orElseThrow();
			return new MyGroup(g.getId(), g.getName(), periodService.streak(g).current(), achievementRate(g, m));
		}).toList();
	}

	/** 내가 활동 멤버였던 마감 기간 중 목표를 채운 비율(%), 마감 기간이 없으면 0 */
	private int achievementRate(Group g, GroupMember m) {
		List<Period> closed = periods.findByGroupIdOrderByPeriodStartAsc(g.getId()).stream()
				.filter(p -> !p.getPeriodStart().isBefore(m.getActiveFrom()))
				.toList();
		if (closed.isEmpty()) return 0;
		Map<LocalDate, Long> perPeriod = checkIns.findByGroupIdAndUserId(g.getId(), m.getUserId()).stream()
				.collect(groupingBy(c -> periodService.periodStart(g, c.getLogicalDate()), counting()));
		long done = closed.stream().filter(p -> perPeriod.getOrDefault(p.getPeriodStart(), 0L) >= g.target()).count();
		return (int) Math.round(100.0 * done / closed.size());
	}

	/** 알림 설정 화면 전체, kinds 는 종류 토글 5개, groups 는 그룹별 음소거 */
	public record GroupMute(Long id, String name, boolean muted) {}
	public record NotificationView(User.NotificationSettings kinds, List<GroupMute> groups) {}

	@GetMapping("/notification-settings")
	public NotificationView notifications(@RequestAttribute("userId") Long userId) {
		return notificationView(users.findById(userId).orElseThrow());
	}

	/** 5개 전체 교체, 빠진 필드는 true 로 들어오니 앱은 항상 5개를 다 보낼 것 */
	@Transactional
	@PutMapping("/notification-settings")
	public NotificationView updateNotifications(@RequestAttribute("userId") Long userId,
			@RequestBody User.NotificationSettings body) {
		User u = users.lockById(userId).orElseThrow();
		u.setNotifications(body);
		return notificationView(users.save(u));
	}

	private NotificationView notificationView(User u) {
		List<GroupMute> mutes = members.findByUserId(u.getId()).stream()
				.map(m -> new GroupMute(m.getGroupId(), groups.findById(m.getGroupId()).orElseThrow().getName(), m.isMuted()))
				.toList();
		return new NotificationView(u.getNotifications(), mutes);
	}

	public record PushToken(@NotBlank(message = "토큰이 필요해요") String token,
			@NotBlank(message = "platform 이 필요해요") @Pattern(regexp = "ios|android", message = "platform 은 ios 또는 android") String platform) {}

	/** FCM 토큰 등록, 앱 시작마다 호출해도 되고 같은 토큰은 주인·시각만 갱신 (Phase 6 발송용) */
	@Transactional
	@PostMapping("/push-token")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void registerPushToken(@RequestAttribute("userId") Long userId, @Valid @RequestBody PushToken body) {
		users.lockById(userId).orElseThrow();
		devices.save(new PushDevice(body.token(), userId, body.platform(), periodService.now()));
	}
}
