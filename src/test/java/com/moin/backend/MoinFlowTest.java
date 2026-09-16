package com.moin.backend;

import static org.hamcrest.Matchers.matchesPattern;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.moin.backend.period.PeriodService;

/** API 통합 흐름 검증, 기준 시각 2026-09-16(수) 10:00 KST, 시간은 clock.now 로 이동 */
@SpringBootTest(properties = { "moin.dev-login=true", "spring.datasource.url=jdbc:h2:mem:moin-test",
		"moin.upload-dir=build/test-uploads", "moin.close-interval-ms=3600000" }) // 스케줄러가 테스트 중 끼어들지 않게
@AutoConfigureMockMvc
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

	/** @Primary 로 BackendApplication.clock() 을 대체 */
	@TestConfiguration
	static class TestClock {
		@Bean @Primary
		MutableClock testClock() { return new MutableClock(Instant.parse("2026-09-16T01:00:00Z")); }
	}

	@Autowired MockMvc mvc;
	@Autowired MutableClock clock;
	@Autowired PeriodService periodService;

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
				.andExpect(jsonPath("$.joinsFrom").value("2026-09-17"));

		mvc.perform(post("/groups/invite/" + code + "/join").header("Authorization", "Bearer " + friend))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.card.joinsNextPeriod").value(true))
				.andExpect(jsonPath("$.card.state").value("WAITING_OTHERS"))
				.andExpect(jsonPath("$.card.activeCount").value(1)); // 이번 기간엔 집계 안 됨
		mvc.perform(post("/groups/invite/" + code + "/join").header("Authorization", "Bearer " + friend))
				.andExpect(status().isConflict())
				.andExpect(status().reason("이미 참여한 그룹이에요"));
		mvc.perform(get("/groups/invite/" + code).header("Authorization", "Bearer " + friend))
				.andExpect(jsonPath("$.memberCount").value(2))
				.andExpect(jsonPath("$.alreadyMember").value(true));
		mvc.perform(get("/groups").header("Authorization", "Bearer " + friend))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].joinsNextPeriod").value(true));

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
				.andExpect(status().isConflict()); // 다음 기간부터
		mvc.perform(multipart(checkInUrl).file(text).header("Authorization", "Bearer " + owner))
				.andExpect(status().isUnsupportedMediaType());
		String checkedIn = mvc.perform(multipart(checkInUrl).file(video).header("Authorization", "Bearer " + owner))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.logicalDate").value("2026-09-16"))
				.andExpect(jsonPath("$.allComplete").value(true)) // 활동 멤버가 나 혼자
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
				.andExpect(jsonPath("$.card.completedCount").value(1))
				.andExpect(jsonPath("$.card.members[0].videoUrl").value(videoUrl))
				.andExpect(jsonPath("$.periodVideoCount").value(1))
				.andExpect(jsonPath("$.streak.current").value(0)); // 아직 마감 전
		mvc.perform(get("/groups/" + groupId).header("Authorization", "Bearer " + login("외부인")))
				.andExpect(status().isForbidden());
		mvc.perform(get("/groups/9999").header("Authorization", "Bearer " + owner))
				.andExpect(status().isNotFound());
		mvc.perform(get("/me").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.totalCheckIns").value(1));

		// --- 하루 지나 마감, 나 혼자 인증했으니 PERFECT, 스트릭 1, 친구는 이제 활동 멤버 ---
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

		// --- 피드: 9/16 은 방장 혼자, 9/17 은 친구만 인증, 오늘(9/21)은 아무도 ---
		String feedUrl = "/groups/" + groupId + "/check-ins";
		mvc.perform(get(feedUrl).param("date", "2026-09-16").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.date").value("2026-09-16"))
				.andExpect(jsonPath("$.completedCount").value(1))
				.andExpect(jsonPath("$.activeCount").value(1)) // 친구는 9/17 부터
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

		// --- 달력: 9월 마감 5개(PERFECT, PASS, FAILED x3), 인증 2건, 완벽 비율 20% ---
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
				.andExpect(jsonPath("$.totalCheckIns").value(2))
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

		// --- 마이페이지 내 그룹: 방장은 마감 5개 중 1개 완료(20%), 친구는 9/17 이후 4개 중 1개(25%) ---
		mvc.perform(get("/me/groups").header("Authorization", "Bearer " + owner))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("저녁 러닝"))
				.andExpect(jsonPath("$[0].streak").value(0))
				.andExpect(jsonPath("$[0].achievementRate").value(20));
		mvc.perform(get("/me/groups").header("Authorization", "Bearer " + friend))
				.andExpect(jsonPath("$[0].achievementRate").value(25));

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
