package com.moin.backend;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.moin.backend.group.GroupMemberRepository;
import com.moin.backend.group.PendingMembershipMigration;
import com.moin.backend.notification.NotificationScheduler;
import com.moin.backend.notification.PushSender;
import com.moin.backend.period.PeriodService;
import com.moin.backend.user.PushDeviceRepository;

/** API 통합 흐름 검증, 기준 시각 2026-09-16(수) 10:00 KST, 시간은 clock.now 로 이동 */
@SpringBootTest(properties = { "moin.dev-login=true", "spring.datasource.url=jdbc:h2:mem:moin-test",
		"moin.upload-dir=build/test-uploads", "moin.close-interval-ms=3600000" }) // 스케줄러가 테스트 중 끼어들지 않게
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MoinFlowTest.TestClock.class)
class MoinFlowTest {

	/** now 를 바꾸면 이후 요청은 그 시각 기준으로 처리 */
	static class MutableClock extends Clock {
		Instant now;
		MutableClock(Instant now) { this.now = now; }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return now; }
	}

	/** FCM 대신 보낸 내용을 쌓아 두는 발송기, 토큰이 없으면 실제 발송기처럼 아무것도 안 함 */
	record Sent(List<String> tokens, String title, String body, Map<String, String> data) {}
	static class RecordingSender extends PushSender {
		final List<Sent> sent = new ArrayList<>();
		RecordingSender(PushDeviceRepository devices) throws java.io.IOException { super(devices, ""); }
		@Override public void send(List<String> tokens, String title, String body, Map<String, String> data) {
			if (!tokens.isEmpty()) sent.add(new Sent(tokens, title, body, data));
		}
	}

	/** @Primary 로 BackendApplication.clock() 과 PushSender 를 대체 */
	@TestConfiguration
	static class TestClock {
		@Bean @Primary
		MutableClock testClock() { return new MutableClock(Instant.parse("2026-09-16T01:00:00Z")); }
		@Bean @Primary
		RecordingSender recordingSender(PushDeviceRepository devices) throws java.io.IOException { return new RecordingSender(devices); }
	}

	@Autowired MockMvc mvc;
	@Autowired MutableClock clock;
	@Autowired PeriodService periodService;
	@Autowired NotificationScheduler notificationScheduler;
	@Autowired RecordingSender sender;
	@Autowired PushDeviceRepository devices;
	@Autowired GroupMemberRepository memberships;
	@Autowired PendingMembershipMigration membershipMigration;
	@Autowired com.moin.backend.checkin.CheckInCommentRepository comments;

	@Test
	@org.springframework.transaction.annotation.Transactional
	void 인증_댓글은_그룹_멤버만_작성하고_인증별로_페이지를_조회한다() throws Exception {
		String owner = login("댓글 작성자");
		String outsider = login("댓글 외부인");
		String group = mvc.perform(json(post("/groups"), owner).content("{\"name\":\"댓글 테스트\",\"frequency\":\"DAILY\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long groupId = ((Number) JsonPath.read(group, "$.card.id")).longValue();
		String checkIn = mvc.perform(multipart("/groups/" + groupId + "/check-ins")
				.file(new MockMultipartFile("video", "comment.mp4", "video/mp4", new byte[] { 1, 2, 3 }))
				.header("Authorization", "Bearer " + owner)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long checkInId = ((Number) JsonPath.read(checkIn, "$.id")).longValue();
		long userId = ((Number) JsonPath.read(checkIn, "$.group.members[0].userId")).longValue();
		assertEquals(checkInId, ((Number) JsonPath.read(checkIn, "$.group.members[0].checkInId")).longValue());
		String url = "/groups/" + groupId + "/check-ins/" + checkInId + "/comments";
		mvc.perform(get(url)).andExpect(status().isUnauthorized());
		mvc.perform(json(get(url), outsider)).andExpect(status().isForbidden());
		mvc.perform(json(post(url), outsider).content("{\"body\":\"침입\"}")).andExpect(status().isForbidden());
		String otherGroup = mvc.perform(json(post("/groups"), owner).content("{\"name\":\"다른 그룹\",\"frequency\":\"DAILY\"}"))
				.andReturn().getResponse().getContentAsString();
		long otherId = ((Number) JsonPath.read(otherGroup, "$.card.id")).longValue();
		String wrongUrl = "/groups/" + otherId + "/check-ins/" + checkInId + "/comments";
		mvc.perform(json(get(wrongUrl), owner)).andExpect(status().isNotFound());
		mvc.perform(json(post(wrongUrl), owner).content("{\"body\":\"다른 인증\"}")).andExpect(status().isNotFound());
		mvc.perform(json(post(url), owner).content("{\"body\":\"  \"}")).andExpect(status().isBadRequest());
		mvc.perform(json(post(url), owner).content("{\"body\":\"" + "가".repeat(501) + "\"}")).andExpect(status().isBadRequest());
		mvc.perform(json(post(url), owner).content("{\"body\":\"  멋져요!  \"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.body").value("멋져요!"))
				.andExpect(jsonPath("$.nickname").value("댓글 작성자")).andExpect(jsonPath("$.userId").value(userId));
		mvc.perform(json(get(url), owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].body").value("멋져요!")).andExpect(jsonPath("$.nextCursor").isEmpty());
		for (int i = 0; i < 30; i++) comments.save(new com.moin.backend.checkin.CheckInComment(checkInId, userId, "댓글 " + i, clock.instant()));
		comments.flush();
		String page = mvc.perform(json(get(url), owner)).andExpect(jsonPath("$.items.length()").value(30))
				.andExpect(jsonPath("$.items[0].body").value("댓글 29")).andReturn().getResponse().getContentAsString();
		long cursor = ((Number) JsonPath.read(page, "$.nextCursor")).longValue();
		mvc.perform(json(get(url).param("before", Long.toString(cursor)), owner))
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].body").value("멋져요!"))
				.andExpect(jsonPath("$.nextCursor").isEmpty());
		mvc.perform(json(get(url).param("before", "0"), owner)).andExpect(status().isBadRequest());
	}

	@Test
	void 프로필_사진과_닉네임은_본인만_변경하고_재로그인에도_유지한다() throws Exception {
		String owner = login("프로필 원래 이름");
		String other = login("프로필 다른 사용자");
		var bytes = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(24, 16, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
		var photo = new MockMultipartFile("avatar", "photo.png", "image/png", bytes.toByteArray());
		mvc.perform(multipart("/me/profile").file(photo).param("nickname", "미인증 변경"))
				.andExpect(status().isUnauthorized());
		String updated = mvc.perform(multipart("/me/profile").file(photo).param("nickname", "  새 닉네임  ").header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value("새 닉네임"))
				.andExpect(jsonPath("$.avatarUrl", startsWith("/avatars/")))
				.andReturn().getResponse().getContentAsString();
		String avatar = JsonPath.read(updated, "$.avatarUrl");
		byte[] image = mvc.perform(get(avatar)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		var normalized = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image));
		assertEquals(512, normalized.getWidth());
		assertEquals(512, normalized.getHeight());
		mvc.perform(get("/me").header("Authorization", "Bearer " + other))
				.andExpect(jsonPath("$.nickname").value("프로필 다른 사용자"))
				.andExpect(jsonPath("$.avatarUrl").isEmpty());
		mvc.perform(post("/auth/dev").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"프로필 원래 이름\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value("새 닉네임"))
				.andExpect(jsonPath("$.avatarUrl").value(avatar));
		mvc.perform(multipart("/me/profile").param("nickname", " ").header("Authorization", "Bearer " + owner))
				.andExpect(status().isBadRequest());
		mvc.perform(multipart("/me/profile").param("nickname", "123456789012345678901").header("Authorization", "Bearer " + owner))
				.andExpect(status().isBadRequest());
		mvc.perform(multipart("/me/profile").file(new MockMultipartFile("avatar", "fake.jpg", "image/jpeg", new byte[] { 1, 2, 3 }))
				.param("nickname", "저장되면 안 됨").header("Authorization", "Bearer " + owner))
				.andExpect(status().isUnsupportedMediaType());
		mvc.perform(get("/me").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.nickname").value("새 닉네임"))
				.andExpect(jsonPath("$.avatarUrl").value(avatar));
		mvc.perform(multipart("/me/profile").param("nickname", "닉네임만 변경").header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value("닉네임만 변경"))
				.andExpect(jsonPath("$.avatarUrl").value(avatar));
	}

	@Test
	@org.springframework.transaction.annotation.Transactional
	void 주중_참여와_기존_대기는_즉시_인증하고_지난_기간은_유지한다() throws Exception {
		Instant previous = clock.now;
		try {
			clock.now = Instant.parse("2026-09-09T01:00:00Z");
			String owner = login("주간 방장");
			String friend = login("주간 새 멤버");
			String created = mvc.perform(json(post("/groups"), owner)
					.content("{\"name\":\"주간 인증\",\"frequency\":\"WEEKLY\",\"weeklyTarget\":2}"))
					.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
			long groupId = ((Number) JsonPath.read(created, "$.card.id")).longValue();
			String code = JsonPath.read(created, "$.inviteCode");
			clock.now = Instant.parse("2026-09-16T01:00:00Z");
			mvc.perform(get("/groups/invite/" + code).header("Authorization", "Bearer " + friend))
					.andExpect(jsonPath("$.joinsFrom").value("2026-09-14"));
			mvc.perform(post("/groups/invite/" + code + "/join").header("Authorization", "Bearer " + friend))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.card.joinsNextPeriod").value(false))
					.andExpect(jsonPath("$.card.activeCount").value(2));
			var pending = memberships.findByGroupId(groupId).stream()
					.filter(m -> m.getActiveFrom().toString().equals("2026-09-14")).findFirst().orElseThrow();
			pending.setActiveFrom(java.time.LocalDate.parse("2026-09-21"));
			memberships.saveAndFlush(pending);
			membershipMigration.run(null);
			membershipMigration.run(null); // 재시작해도 현재·과거 기간은 그대로
			mvc.perform(get("/groups/" + groupId).header("Authorization", "Bearer " + friend))
					.andExpect(jsonPath("$.card.joinsNextPeriod").value(false))
					.andExpect(jsonPath("$.card.activeCount").value(2));
			String checkInUrl = "/groups/" + groupId + "/check-ins";
			MockMultipartFile video = new MockMultipartFile("video", "weekly.mp4", "video/mp4", new byte[] { 1, 2, 3 });
			mvc.perform(multipart(checkInUrl).file(video).header("Authorization", "Bearer " + friend))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.group.myDoneCount").value(1))
					.andExpect(jsonPath("$.group.myDone").value(false));
			clock.now = clock.now.plus(Duration.ofDays(1));
			mvc.perform(multipart(checkInUrl).file(video).header("Authorization", "Bearer " + friend))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.group.myDoneCount").value(2))
					.andExpect(jsonPath("$.group.myDone").value(true));
			mvc.perform(get(checkInUrl).param("date", "2026-09-09").header("Authorization", "Bearer " + owner))
					.andExpect(jsonPath("$.activeCount").value(1));
		} finally {
			clock.now = previous;
		}
	}

	@Test
	void 로그아웃은_현재_세션과_본인_기기만_해제한다() throws Exception {
		String current = login("로그아웃 검증");
		String otherSession = login("로그아웃 검증");
		String anotherUser = login("다른 계정 검증");
		mvc.perform(json(post("/me/push-token"), current).content("{\"token\":\"logout-device\",\"platform\":\"ios\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(json(post("/me/push-token"), anotherUser).content("{\"token\":\"other-device\",\"platform\":\"ios\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(json(post("/me/logout"), current).content("{\"pushToken\":\"logout-device\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(get("/me").header("Authorization", "Bearer " + current)).andExpect(status().isUnauthorized());
		mvc.perform(get("/me").header("Authorization", "Bearer " + otherSession)).andExpect(status().isOk());
		assertEquals(false, devices.existsById("logout-device"));
		mvc.perform(json(post("/me/logout"), otherSession).content("{\"pushToken\":\"other-device\"}"))
				.andExpect(status().isNoContent());
		assertEquals(true, devices.existsById("other-device"));
		mvc.perform(get("/me").header("Authorization", "Bearer " + anotherUser)).andExpect(status().isOk());
		mvc.perform(post("/me/logout")).andExpect(status().isUnauthorized());
	}

	@Test
	void 배포_상태확인은_인증없이_가능하고_업무API는_보호된다() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist());
		mvc.perform(get("/groups")).andExpect(status().isUnauthorized());
	}

	@Test
	void 로그인_그룹생성_홈_초대_참여_인증_마감_스트릭() throws Exception {
		String owner = login("정혁");

		// --- 인증 ---
		mvc.perform(get("/groups")).andExpect(status().isUnauthorized());
		mvc.perform(get("/groups").header("Authorization", "Bearer nope")).andExpect(status().isUnauthorized());
		mvc.perform(get("/groups").header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());

		// --- 그룹 생성 ---
		String created = mvc.perform(json(post("/groups"), owner).content("{\"name\":\"모닝 러닝\",\"frequency\":\"DAILY\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.card.name").value("모닝 러닝"))
				.andExpect(jsonPath("$.card.state").value("NEEDS_ME"))
				.andExpect(jsonPath("$.card.activeCount").value(1))
				.andExpect(jsonPath("$.card.completedCount").value(0))
				.andExpect(jsonPath("$.card.resetTime").value("04:00"))
				.andExpect(jsonPath("$.card.allowedAbsences").value(1))
				.andExpect(jsonPath("$.card.periodStart").value("2026-09-16"))
				.andExpect(jsonPath("$.card.deadline").value("2026-09-16T19:00:00Z")) // 9/17 04:00 KST
				.andExpect(jsonPath("$.card.members[0].nickname").value("정혁"))
				.andExpect(jsonPath("$.isOwner").value(true))
				.andExpect(jsonPath("$.inviteCode", matchesPattern("[A-HJ-NP-Z2-9]{6}")))
				.andExpect(jsonPath("$.reminderTime").value("08:00"))
				.andExpect(jsonPath("$.streak.current").value(0))
				.andExpect(jsonPath("$.threshold").value(0))
				.andExpect(jsonPath("$.monthClosedPeriods").value(0))
				.andReturn().getResponse().getContentAsString();
		long groupId = ((Number) JsonPath.read(created, "$.card.id")).longValue();
		String code = JsonPath.read(created, "$.inviteCode");

		mvc.perform(json(post("/groups"), owner).content("{\"name\":\"요가\",\"frequency\":\"WEEKLY\"}"))
				.andExpect(status().isBadRequest()); // weeklyTarget 없음
		mvc.perform(json(post("/groups"), owner).content("{\"name\":\"\",\"frequency\":\"DAILY\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("그룹 이름을 입력해주세요")); // 검증 문구가 message 로

		mvc.perform(get("/groups").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("모닝 러닝"))
				.andExpect(jsonPath("$[0].myDone").value(false))
				.andExpect(jsonPath("$[0].joinsNextPeriod").value(false))
				.andExpect(jsonPath("$[0].streak").value(0));

		mvc.perform(get("/me").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.nickname").value("정혁"))
				.andExpect(jsonPath("$.totalStreak").value(0))
				.andExpect(jsonPath("$.totalCheckIns").value(0));

		// 같은 닉네임으로 다시 로그인하면 같은 사용자
		String again = login("정혁");
		mvc.perform(get("/groups").header("Authorization", "Bearer " + again))
				.andExpect(jsonPath("$.length()").value(1));

		// --- 초대 · 참여 ---
		String friend = login("지연");
		mvc.perform(get("/groups/invite/ZZZZZZ").header("Authorization", "Bearer " + friend))
				.andExpect(status().isNotFound());
		mvc.perform(get("/groups/invite/" + code.toLowerCase()).header("Authorization", "Bearer " + friend))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("모닝 러닝"))
				.andExpect(jsonPath("$.memberCount").value(1))
				.andExpect(jsonPath("$.alreadyMember").value(false))
				.andExpect(jsonPath("$.joinsFrom").value("2026-09-16"));

		mvc.perform(post("/groups/invite/" + code + "/join").header("Authorization", "Bearer " + friend))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.card.joinsNextPeriod").value(false))
				.andExpect(jsonPath("$.isOwner").value(false))
				.andExpect(jsonPath("$.card.state").value("NEEDS_ME"))
				.andExpect(jsonPath("$.card.activeCount").value(2)); // 참여 즉시 집계
		mvc.perform(post("/groups/invite/" + code + "/join").header("Authorization", "Bearer " + friend))
				.andExpect(status().isConflict())
				.andExpect(status().reason("이미 참여한 그룹이에요"));
		mvc.perform(get("/groups/invite/" + code).header("Authorization", "Bearer " + friend))
				.andExpect(jsonPath("$.memberCount").value(2))
				.andExpect(jsonPath("$.alreadyMember").value(true));
		mvc.perform(get("/groups").header("Authorization", "Bearer " + friend))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].joinsNextPeriod").value(false));

		// --- 이름 변경: 방장만 ---
		mvc.perform(json(patch("/groups/" + groupId), friend).content("{\"name\":\"저녁 러닝\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(json(patch("/groups/" + groupId), owner).content("{\"name\":\"저녁 러닝\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.card.name").value("저녁 러닝"));
		mvc.perform(json(patch("/groups/" + groupId), owner).content("{\"name\":\"123456789012345678901\"}"))
				.andExpect(status().isBadRequest()); // 21자
		mvc.perform(json(patch("/groups/9999"), owner).content("{\"name\":\"x\"}"))
				.andExpect(status().isNotFound());

		// --- 인증 업로드 ---
		String checkInUrl = "/groups/" + groupId + "/check-ins";
		MockMultipartFile video = new MockMultipartFile("video", "a.mp4", "video/mp4", new byte[] { 1, 2, 3 });
		MockMultipartFile text = new MockMultipartFile("video", "a.txt", "text/plain", new byte[] { 1 });
		mvc.perform(multipart(checkInUrl).file(video).header("Authorization", "Bearer " + friend))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.allComplete").value(false)); // 참여 직후 인증 가능
		mvc.perform(multipart(checkInUrl).file(text).header("Authorization", "Bearer " + owner))
				.andExpect(status().isUnsupportedMediaType());
		String checkedIn = mvc.perform(multipart(checkInUrl).file(video).header("Authorization", "Bearer " + owner))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.logicalDate").value("2026-09-16"))
				.andExpect(jsonPath("$.allComplete").value(true)) // 친구와 방장 모두 인증
				.andExpect(jsonPath("$.videoUrl", startsWith("/videos/")))
				.andExpect(jsonPath("$.group.state").value("COMPLETE"))
				.andExpect(jsonPath("$.group.myDone").value(true))
				.andReturn().getResponse().getContentAsString();
		String videoUrl = JsonPath.read(checkedIn, "$.videoUrl");
		mvc.perform(get(videoUrl)).andExpect(status().isOk()); // 영상은 토큰 없이 서빙
		mvc.perform(multipart(checkInUrl).file(video).header("Authorization", "Bearer " + owner))
				.andExpect(status().isConflict())
				.andExpect(status().reason("오늘은 이미 인증했어요"));

		// --- 상세 ---
		mvc.perform(get("/groups/" + groupId).header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.card.completedCount").value(2))
				.andExpect(jsonPath("$.card.members[0].videoUrl").value(videoUrl))
				.andExpect(jsonPath("$.periodVideoCount").value(2))
				.andExpect(jsonPath("$.streak.current").value(0)); // 아직 마감 전
		mvc.perform(get("/groups/" + groupId).header("Authorization", "Bearer " + login("외부인")))
				.andExpect(status().isForbidden());
		mvc.perform(get("/groups/9999").header("Authorization", "Bearer " + owner))
				.andExpect(status().isNotFound());
		mvc.perform(get("/me").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.totalCheckIns").value(1));

		// --- 하루 지나 마감, 참여 당일 둘 다 인증해 PERFECT, 스트릭 1 ---
		clock.now = clock.now.plus(Duration.ofDays(1)); // 9/17 10:00 KST
		periodService.closeAllDuePeriods();
		mvc.perform(get("/groups/" + groupId).header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.streak.current").value(1))
				.andExpect(jsonPath("$.streak.perfect").value(1))
				.andExpect(jsonPath("$.streak.longest").value(1))
				.andExpect(jsonPath("$.card.activeCount").value(2))
				.andExpect(jsonPath("$.card.completedCount").value(0))
				.andExpect(jsonPath("$.card.state").value("NEEDS_ME"))
				.andExpect(jsonPath("$.card.periodStart").value("2026-09-17"))
				.andExpect(jsonPath("$.threshold").value(1))
				.andExpect(jsonPath("$.monthClosedPeriods").value(1))
				.andExpect(jsonPath("$.monthCompletedPeriods").value(1));
		mvc.perform(get("/groups").header("Authorization", "Bearer " + friend))
				.andExpect(jsonPath("$[0].joinsNextPeriod").value(false))
				.andExpect(jsonPath("$[0].state").value("NEEDS_ME"));
		mvc.perform(multipart(checkInUrl).file(video).header("Authorization", "Bearer " + friend))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.allComplete").value(false)) // 방장이 아직
				.andExpect(jsonPath("$.group.state").value("WAITING_OTHERS"));

		// --- 하루 더, 방장 미인증 1명 <= 결석 허용 1 이라 PASS, 스트릭 2 ---
		clock.now = clock.now.plus(Duration.ofDays(1)); // 9/18
		periodService.closeAllDuePeriods();
		periodService.closeAllDuePeriods(); // 중복 실행해도 기간이 늘지 않음
		mvc.perform(get("/groups/" + groupId).header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.streak.current").value(2))
				.andExpect(jsonPath("$.streak.perfect").value(1))
				.andExpect(jsonPath("$.streak.pass").value(1))
				.andExpect(jsonPath("$.monthClosedPeriods").value(2));

		// --- 사흘 방치, 2명 미인증 > 결석 허용 1 이라 FAILED, 스트릭 0, 최장 2 ---
		clock.now = clock.now.plus(Duration.ofDays(3)); // 9/21, 빠진 기간 3개를 한 번에 채움
		periodService.closeAllDuePeriods();
		mvc.perform(get("/groups/" + groupId).header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.streak.current").value(0))
				.andExpect(jsonPath("$.streak.longest").value(2))
				.andExpect(jsonPath("$.monthClosedPeriods").value(5))
				.andExpect(jsonPath("$.monthCompletedPeriods").value(2));

		// --- 피드: 참여 당일 둘 다 인증, 다음 날 친구만 인증, 오늘은 미인증 ---
		String feedUrl = "/groups/" + groupId + "/check-ins";
		mvc.perform(get(feedUrl).param("date", "2026-09-16").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.date").value("2026-09-16"))
				.andExpect(jsonPath("$.completedCount").value(2))
				.andExpect(jsonPath("$.activeCount").value(2))
				.andExpect(jsonPath("$.members[0].nickname").value("정혁"))
				.andExpect(jsonPath("$.members[0].videoUrl").value(videoUrl));
		mvc.perform(get(feedUrl).param("date", "2026-09-17").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.completedCount").value(1))
				.andExpect(jsonPath("$.activeCount").value(2))
				.andExpect(jsonPath("$.members[0].nickname").value("지연")) // 영상 있는 멤버가 앞
				.andExpect(jsonPath("$.members[1].nickname").value("정혁"))
				.andExpect(jsonPath("$.members[1].videoUrl").isEmpty());
		mvc.perform(get(feedUrl).header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.date").value("2026-09-21"))
				.andExpect(jsonPath("$.completedCount").value(0))
				.andExpect(jsonPath("$.activeCount").value(2));
		mvc.perform(get(feedUrl).param("date", "어제").header("Authorization", "Bearer " + owner))
				.andExpect(status().isBadRequest());
		mvc.perform(get(feedUrl).header("Authorization", "Bearer " + login("외부인")))
				.andExpect(status().isForbidden());

		// --- 달력: 9월 마감 5개(PERFECT, PASS, FAILED x3), 인증 3건, 완벽 비율 20% ---
		String calendarUrl = "/groups/" + groupId + "/calendar";
		mvc.perform(get(calendarUrl).param("month", "2026-09").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.month").value("2026-09"))
				.andExpect(jsonPath("$.today").value("2026-09-21"))
				.andExpect(jsonPath("$.periods.length()").value(5))
				.andExpect(jsonPath("$.periods[0].start").value("2026-09-16"))
				.andExpect(jsonPath("$.periods[0].end").value("2026-09-17"))
				.andExpect(jsonPath("$.periods[0].status").value("PERFECT"))
				.andExpect(jsonPath("$.periods[1].status").value("PASS"))
				.andExpect(jsonPath("$.periods[4].status").value("FAILED"))
				.andExpect(jsonPath("$.totalCheckIns").value(3))
				.andExpect(jsonPath("$.longestStreak").value(2))
				.andExpect(jsonPath("$.perfectRate").value(20));
		mvc.perform(get(calendarUrl).header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.month").value("2026-09")); // 기본값은 이번 달
		mvc.perform(get(calendarUrl).param("month", "2026-10").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.periods").isEmpty())
				.andExpect(jsonPath("$.totalCheckIns").value(0))
				.andExpect(jsonPath("$.perfectRate").value(0));
		mvc.perform(get(calendarUrl).param("month", "9월").header("Authorization", "Bearer " + owner))
				.andExpect(status().isBadRequest());

		// --- 마이페이지 내 그룹: 방장은 마감 5개 중 1개 완료(20%), 친구는 5개 중 2개(40%) ---
		mvc.perform(get("/me/groups").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("저녁 러닝"))
				.andExpect(jsonPath("$[0].streak").value(0))
				.andExpect(jsonPath("$[0].achievementRate").value(20));
		mvc.perform(get("/me/groups").header("Authorization", "Bearer " + friend))
				.andExpect(jsonPath("$[0].achievementRate").value(40));

		// --- 알림 설정: 기본 전부 켬, 그룹 음소거는 멤버만 ---
		mvc.perform(get("/me/notification-settings").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.kinds.reminder").value(true))
				.andExpect(jsonPath("$.kinds.allComplete").value(true))
				.andExpect(jsonPath("$.groups[0].id").value(groupId))
				.andExpect(jsonPath("$.groups[0].muted").value(false));
		mvc.perform(json(put("/groups/" + groupId + "/mute"), login("외부인")).content("{\"muted\":true}"))
				.andExpect(status().isForbidden());
		mvc.perform(json(put("/groups/" + groupId + "/mute"), owner).content("{\"muted\":true}"))
				.andExpect(status().isNoContent());
		mvc.perform(get("/me/notification-settings").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.groups[0].muted").value(true));
		mvc.perform(get("/groups/" + groupId).header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.muted").value(true));
		mvc.perform(get("/me/notification-settings").header("Authorization", "Bearer " + friend))
				.andExpect(jsonPath("$.groups[0].muted").value(false)); // 내 음소거는 나만
		mvc.perform(json(put("/me/notification-settings"), owner)
						.content("{\"reminder\":false,\"social\":true,\"crisis\":true,\"lastCall\":false,\"allComplete\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kinds.reminder").value(false))
				.andExpect(jsonPath("$.kinds.lastCall").value(false))
				.andExpect(jsonPath("$.kinds.social").value(true));

		// --- 푸시 토큰: 재등록 가능, 같은 토큰을 다른 계정이 등록하면 주인이 바뀜 ---
		mvc.perform(json(post("/me/push-token"), owner).content("{\"token\":\"fcm-1\",\"platform\":\"ios\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(json(post("/me/push-token"), owner).content("{\"token\":\"fcm-1\",\"platform\":\"ios\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(json(post("/me/push-token"), friend).content("{\"token\":\"fcm-1\",\"platform\":\"android\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(json(post("/me/push-token"), owner).content("{\"token\":\"fcm-2\",\"platform\":\"web\"}"))
				.andExpect(status().isBadRequest());

		// --- 푸시: 방장은 리마인더 OFF + 그룹 음소거, 친구(fcm-1)만 받는다 ---
		mvc.perform(json(post("/me/push-token"), owner).content("{\"token\":\"fcm-owner\",\"platform\":\"ios\"}"))
				.andExpect(status().isNoContent());
		List<Sent> sent = sender.sent;
		notificationScheduler.tick(); // 9/21 10:00 KST, 리마인더 08:00 지남, 둘 다 미인증
		assertEquals(1, sent.size());
		assertEquals(List.of("fcm-1"), sent.get(0).tokens());
		assertEquals("REMINDER", sent.get(0).data().get("kind"));
		assertEquals(String.valueOf(groupId), sent.get(0).data().get("groupId"));
		notificationScheduler.tick();
		assertEquals(1, sent.size()); // 같은 날 두 번 안 보냄

		clock.now = Instant.parse("2026-09-21T17:30:00Z"); // 9/22 02:30 KST, 논리 날짜는 아직 9/21, 마감 04:00 까지 2시간 안
		notificationScheduler.tick();
		assertEquals(2, sent.size());
		assertEquals("CRISIS", sent.get(1).data().get("kind")); // 2명 미인증 > 결석 허용 1
		assertEquals(List.of("fcm-1"), sent.get(1).tokens());
		notificationScheduler.tick();
		assertEquals(2, sent.size()); // 기간당 1번

		mvc.perform(multipart(checkInUrl).file(video).header("Authorization", "Bearer " + friend))
				.andExpect(status().isCreated());
		assertEquals(2, sent.size()); // SOCIAL 대상은 방장뿐인데 음소거라 발송 없음
		mvc.perform(multipart(checkInUrl).file(video).header("Authorization", "Bearer " + owner))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.allComplete").value(true));
		assertEquals(3, sent.size());
		assertEquals("ALL_COMPLETE", sent.get(2).data().get("kind"));
		assertEquals(List.of("fcm-1"), sent.get(2).tokens());

		// --- 앱 버전: 인증 없이 ---
		mvc.perform(get("/app/version"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.minVersion").value("1.0.0"))
				.andExpect(jsonPath("$.latestVersion").value("1.0.0"));
	}

	@Autowired com.moin.backend.user.UserRepository users;
	@Autowired com.moin.backend.freeze.FreezeShopService freezeShop;
	@Autowired com.moin.backend.freeze.AdmobVerifier admobVerifier;

	@Test
	@org.springframework.transaction.annotation.Transactional
	void 프리즈는_월무료분부터_쓰고_광고는_검증후_주한번만_지급한다() throws Exception {
		Instant before = clock.now;
		String adUnit = "ca-app-pub-test/12345";
		Object previousUnit = org.springframework.test.util.ReflectionTestUtils.getField(freezeShop, "androidUnit");
		try {
			clock.now = Instant.parse("2026-09-16T01:00:00Z");
			String owner = login("프리즈 사용자");
			String outsider = login("프리즈 외부인");
			String body = mvc.perform(json(post("/groups"), owner).content("{\"name\":\"프리즈 테스트\",\"frequency\":\"DAILY\",\"allowedAbsences\":0,\"streakFreeze\":true}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
			long id = ((Number) JsonPath.read(body, "$.card.id")).longValue();
			long userId = memberships.findByGroupId(id).get(0).getUserId();
			users.findById(userId).orElseThrow().setFreezeBalance(1);
			String path = "/groups/" + id + "/freezes";
			mvc.perform(json(get(path).param("month", "2026-09"), owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.monthlyRemaining").value(1)).andExpect(jsonPath("$.balance").value(1));
			mvc.perform(json(post(path), outsider).content("{\"date\":\"2026-09-16\"}")).andExpect(status().isForbidden());
			mvc.perform(json(post(path), owner).content("{\"date\":\"2026-09-15\"}")).andExpect(status().isBadRequest());
			mvc.perform(json(post(path), owner).content("{\"date\":\"2026-09-17\"}")).andExpect(status().isBadRequest());
			clock.now = clock.now.plus(Duration.ofDays(1));
			mvc.perform(json(post(path), owner).content("{\"date\":\"2026-09-16\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.monthlyRemaining").value(0)).andExpect(jsonPath("$.balance").value(1));
			mvc.perform(json(get("/groups/" + id + "/calendar").param("month", "2026-09"), owner))
				.andExpect(jsonPath("$.periods[0].status").value("FROZEN")).andExpect(jsonPath("$.longestStreak").value(1));
			mvc.perform(json(post(path), owner).content("{\"date\":\"2026-09-16\"}")).andExpect(status().isConflict());
			mvc.perform(json(post(path), owner).content("{\"date\":\"2026-09-17\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(0));
			clock.now = clock.now.plus(Duration.ofDays(1));
			mvc.perform(json(post(path), owner).content("{\"date\":\"2026-09-18\"}")).andExpect(status().isConflict());
			mvc.perform(json(post("/me/freezes/purchases"), owner).content("{\"platform\":\"android\",\"token\":\"forged\"}"))
				.andExpect(status().isServiceUnavailable());
			mvc.perform(get("/callbacks/admob").queryParam("custom_data", "forged")).andExpect(status().isBadRequest());

			// 로컬 서명 키로 원문 검증 후 실제 지급 경로 실행, 변조 및 중복 콜백 차단
			org.springframework.test.util.ReflectionTestUtils.setField(freezeShop, "androidUnit", adUnit);
			String session = freezeShop.startAd(userId).id();
			var generator = java.security.KeyPairGenerator.getInstance("EC"); generator.initialize(256);
			var pair = generator.generateKeyPair();
			org.springframework.test.util.ReflectionTestUtils.setField(admobVerifier, "keys", Map.of("test", pair.getPublic()));
			org.springframework.test.util.ReflectionTestUtils.setField(admobVerifier, "fetched", Instant.now());
			String query = "ad_unit=12345&custom_data=" + session + "&reward_amount=1&reward_item=freeze&timestamp=" + clock.now.toEpochMilli() + "&transaction_id=test-reward";
			var signer = java.security.Signature.getInstance("SHA256withECDSA"); signer.initSign(pair.getPrivate());
			signer.update(query.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			String signed = query + "&signature=" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()) + "&key_id=test";
			org.junit.jupiter.api.Assertions.assertThrows(java.security.GeneralSecurityException.class,
				() -> admobVerifier.verify(signed.replace("reward_amount=1", "reward_amount=2")));
			freezeShop.reward(admobVerifier.verify(signed));
			freezeShop.reward(admobVerifier.verify(signed));
			assertEquals(1, freezeShop.wallet(userId).balance());
			org.junit.jupiter.api.Assertions.assertFalse(freezeShop.wallet(userId).adAvailable());
			mvc.perform(json(post("/me/freezes/ad-sessions"), owner)).andExpect(status().isConflict());
			clock.now = Instant.parse("2026-10-01T01:00:00Z");
			mvc.perform(json(get(path).param("month", "2026-09"), owner)).andExpect(jsonPath("$.monthlyRemaining").value(1));
			mvc.perform(json(post(path), owner).content("{\"date\":\"2026-09-18\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.monthlyRemaining").value(0)).andExpect(jsonPath("$.balance").value(1));
			org.junit.jupiter.api.Assertions.assertTrue(freezeShop.wallet(userId).adAvailable());
		} finally {
			clock.now = before;
			org.springframework.test.util.ReflectionTestUtils.setField(freezeShop, "androidUnit", previousUnit);
			org.springframework.test.util.ReflectionTestUtils.setField(admobVerifier, "keys", Map.of());
			org.springframework.test.util.ReflectionTestUtils.setField(admobVerifier, "fetched", Instant.EPOCH);
		}
	}

	private String login(String nickname) throws Exception {
		String body = mvc.perform(post("/auth/dev")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nickname\":\"" + nickname + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value(nickname))
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.token");
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String token) {
		return b.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
	}
}
