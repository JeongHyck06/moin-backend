package com.moin.backend.checkin;

import java.time.LocalDate;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.moin.backend.group.Group;
import com.moin.backend.group.GroupMember;
import com.moin.backend.group.GroupService;
import com.moin.backend.group.GroupService.GroupCard;
import com.moin.backend.period.PeriodService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/groups/{groupId}/check-ins")
@RequiredArgsConstructor
public class CheckInController {

	private final CheckInRepository checkIns;
	private final VideoStorage storage;
	private final GroupService groupService;
	private final PeriodService periodService;

	/** Complete 화면용, allComplete 면 "마지막 1명이었어요, 전원 완료!", streak 은 마감 전 값이라 화면에서 +1 */
	public record CheckInResult(Long id, String videoUrl, LocalDate logicalDate, boolean allComplete,
			PeriodService.Streak streak, GroupCard group) {}

	/**
	 * 3초 영상 업로드 = 오늘 인증, 하루 1번
	 * 검증 뒤 파일 저장, 그다음 row insert 라 insert 가 실패하면 고아 파일이 남을 수 있음 (허용)
	 */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	public CheckInResult create(@RequestAttribute("userId") Long userId, @PathVariable("groupId") Long groupId,
			@RequestPart("video") MultipartFile video) {
		Group g = groupService.get(groupId);
		GroupMember me = groupService.membership(groupId, userId);
		LocalDate periodStart = periodService.currentPeriodStart(g);
		if (me.getActiveFrom().isAfter(periodStart)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "다음 기간부터 인증할 수 있어요");
		}
		LocalDate today = periodService.today(g);
		if (checkIns.existsByGroupIdAndUserIdAndLogicalDate(groupId, userId, today)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "오늘은 이미 인증했어요");
		}
		CheckIn saved = checkIns.save(new CheckIn(groupId, userId, today, storage.save(video), periodService.now()));
		GroupCard card = groupService.card(g, userId);
		return new CheckInResult(saved.getId(), saved.getVideoUrl(), today,
				card.state() == GroupService.State.COMPLETE, periodService.streak(g), card);
	}
}
