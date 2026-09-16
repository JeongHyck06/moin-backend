package com.moin.backend.group;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.moin.backend.group.GroupService.GroupCard;
import com.moin.backend.group.GroupService.GroupDetail;
import com.moin.backend.group.GroupService.InvitePreview;
import com.moin.backend.period.PeriodService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/groups")
@RequiredArgsConstructor
public class GroupController {

	/** 0/O, 1/I 처럼 헷갈리는 글자 제외 */
	private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final GroupRepository groups;
	private final GroupMemberRepository members;
	private final GroupService groupService;
	private final PeriodService periodService;

	public record CreateGroup(
			@NotBlank @Size(max = 20) String name,
			@NotNull Group.Frequency frequency,
			@Min(1) @Max(7) Integer weeklyTarget,
			LocalTime resetTime,
			LocalTime reminderTime,
			@Min(0) @Max(10) Integer allowedAbsences,
			Boolean streakFreeze) {}

	public record Rename(@NotBlank @Size(max = 20) String name) {}

	/** 홈, 상태 순서(NEEDS_ME, WAITING_OTHERS, COMPLETE, CRISIS)로 정렬 */
	@GetMapping
	public List<GroupCard> home(@RequestAttribute("userId") Long userId) {
		return groupService.home(userId);
	}

	/**
	 * 그룹 생성 + 방장 등록, WEEKLY 면 weeklyTarget 필수
	 * firstPeriodStart 는 frequency·resetTime 세팅 뒤에 계산, 순서 바꾸면 NPE
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	public GroupDetail create(@RequestAttribute("userId") Long userId, @Valid @RequestBody CreateGroup body) {
		boolean weekly = body.frequency() == Group.Frequency.WEEKLY;
		if (weekly && body.weeklyTarget() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "주당 횟수를 정해주세요");
		}
		Group g = new Group();
		g.setName(body.name());
		g.setFrequency(body.frequency());
		g.setWeeklyTarget(weekly ? body.weeklyTarget() : null);
		g.setResetTime(Objects.requireNonNullElse(body.resetTime(), LocalTime.of(4, 0)));
		g.setReminderTime(Objects.requireNonNullElse(body.reminderTime(), LocalTime.of(8, 0)));
		g.setAllowedAbsences(Objects.requireNonNullElse(body.allowedAbsences(), 1));
		g.setStreakFreeze(Boolean.TRUE.equals(body.streakFreeze()));
		g.setOwnerId(userId);
		g.setCreatedAt(periodService.now());
		g.setFirstPeriodStart(periodService.currentPeriodStart(g));
		g.setInviteCode(newInviteCode());
		groups.save(g);

		GroupMember owner = members.save(new GroupMember(g.getId(), userId, g.getFirstPeriodStart(), g.getCreatedAt()));
		return groupService.detail(g, owner);
	}

	@GetMapping("/{id}")
	public GroupDetail detail(@RequestAttribute("userId") Long userId, @PathVariable("id") Long id) {
		return groupService.detail(groupService.get(id), groupService.membership(id, userId));
	}

	/** 이름만 바꿀 수 있고, 방장만 */
	@PatchMapping("/{id}")
	public GroupDetail rename(@RequestAttribute("userId") Long userId, @PathVariable("id") Long id, @Valid @RequestBody Rename body) {
		Group g = groupService.get(id);
		GroupMember me = groupService.membership(id, userId);
		if (!g.getOwnerId().equals(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "방장만 바꿀 수 있어요");
		g.setName(body.name());
		return groupService.detail(groups.save(g), me);
	}

	/** 참여 전 미리보기, 멤버가 아니어도 코드만 있으면 조회 가능 */
	@GetMapping("/invite/{code}")
	public InvitePreview preview(@RequestAttribute("userId") Long userId, @PathVariable("code") String code) {
		return groupService.preview(groupService.byInviteCode(code), userId);
	}

	/** 참여, 이번 기간엔 집계 제외, 다음 기간 시작일부터 활동 멤버 */
	@PostMapping("/invite/{code}/join")
	@ResponseStatus(HttpStatus.CREATED)
	public GroupDetail join(@RequestAttribute("userId") Long userId, @PathVariable("code") String code) {
		Group g = groupService.byInviteCode(code);
		if (members.findByGroupIdAndUserId(g.getId(), userId).isPresent()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 참여한 그룹이에요");
		}
		LocalDate joinsFrom = periodService.periodEnd(g, periodService.currentPeriodStart(g));
		GroupMember m = members.save(new GroupMember(g.getId(), userId, joinsFrom, periodService.now()));
		return groupService.detail(g, m);
	}

	/** 6자 랜덤, 32^6 ≈ 10억이라 충돌 재시도는 거의 없음 */
	private String newInviteCode() {
		String code;
		do {
			StringBuilder sb = new StringBuilder(6);
			for (int i = 0; i < 6; i++) sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
			code = sb.toString();
		} while (groups.existsByInviteCode(code));
		return code;
	}
}
