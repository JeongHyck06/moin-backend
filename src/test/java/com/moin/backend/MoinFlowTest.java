package com.moin.backend;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

/** API 통합 흐름 검증, 기준 시각 2026-09-16(수) 10:00 KST, 시간은 clock.now 로 이동 */
@SpringBootTest(properties = { "moin.dev-login=true", "spring.datasource.url=jdbc:h2:mem:moin-test" })
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

	@Test
	void 로그인_그룹생성_홈_프로필_초대_참여_이름변경() throws Exception {
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
				.andExpect(status().isBadRequest()); // 이름 비어 있음

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
				.andExpect(status().isConflict());
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
