package com.moin.backend.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.moin.backend.user.User;
import com.moin.backend.user.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * 카카오 access token 을 서버가 직접 검증하고 자체 세션 토큰(32바이트 랜덤, DB 조회) 발급
 * JWT·Spring Security 는 인터셉터 하나로 충분해서 제외
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRepository users;
	private final SessionRepository sessions;
	private final Clock clock;
	private final IdentityTokenVerifier identityTokens;
	private final RestClient kakao = RestClient.create("https://kapi.kakao.com");

	@Value("${moin.dev-login}")
	private boolean devLogin;

	@Value("${moin.session-days}")
	private int sessionDays;

	public record KakaoLogin(@NotBlank(message = "카카오 토큰이 필요해요") String accessToken) {}
	public record DevLogin(@NotBlank(message = "닉네임을 입력해주세요") @Size(max = 20, message = "닉네임은 20자까지예요") String nickname) {}
	public record LoginResponse(String token, Long userId, String nickname, String avatarUrl) {}
	public record GoogleLogin(@NotBlank @Size(max = 16384) String idToken) {}
	public record AppleLogin(@NotBlank @Size(max = 16384) String identityToken,
			@NotBlank @Size(max = 128) String nonce, @Size(max = 100) String fullName) {}
	public record AppleChallenge(String nonce) {}

	@PostMapping("/google")
	public LoginResponse google(@Valid @RequestBody GoogleLogin body) {
		var identity = identityTokens.google(body.idToken());
		return login("google:" + identity.getSubject(), identity.getClaimAsString("name"), identity.getClaimAsString("picture"));
	}

	@PostMapping("/apple/challenge")
	public AppleChallenge appleChallenge() {
		return new AppleChallenge(identityTokens.challenge());
	}

	@PostMapping("/apple")
	public LoginResponse apple(@Valid @RequestBody AppleLogin body) {
		var identity = identityTokens.apple(body.identityToken(), body.nonce());
		return login("apple:" + identity.getSubject(), body.fullName(), null);
	}
	/** 카카오 /v2/user/me 응답 중 쓰는 필드만, properties 는 동의 항목에 따라 비어 올 수 있음 */
	record KakaoUser(long id, Map<String, Object> properties) {}

	/** 앱이 카카오 SDK로 받은 access token을 서버에서 검증하고 세션 토큰을 발급 */
	@PostMapping("/kakao")
	public LoginResponse kakao(@Valid @RequestBody KakaoLogin body) {
		KakaoUser k;
		try {
			k = kakao.get().uri("/v2/user/me")
					.header("Authorization", "Bearer " + body.accessToken())
					.retrieve().body(KakaoUser.class);
		} catch (RestClientException e) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "카카오 인증에 실패했어요");
		}
		Map<String, Object> props = k.properties() == null ? Map.of() : k.properties();
		return login("kakao:" + k.id(), (String) props.getOrDefault("nickname", "모인"), (String) props.get("profile_image"));
	}

	/** 개발용, moin.dev-login=true 일 때만 열림 */
	@PostMapping("/dev")
	public LoginResponse dev(@Valid @RequestBody DevLogin body) {
		if (!devLogin) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		return login("dev:" + body.nickname(), body.nickname(), null);
	}

	/** 제공자별 고유 ID로 계정 분리, 재로그인 시 사용자가 편집한 프로필 유지 */
	private LoginResponse login(String externalId, String nickname, String avatarUrl) {
		String displayName = nickname == null || nickname.isBlank() ? null : nickname.strip();
		if (displayName != null && displayName.length() > 20) displayName = displayName.substring(0, displayName.offsetByCodePoints(0, Math.min(20, displayName.codePointCount(0, displayName.length()))));
		User user = users.findByExternalId(externalId).orElse(null);
		if (user == null) user = new User(externalId, displayName == null ? "모인" : displayName, avatarUrl);
		users.save(user);

		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		sessions.save(new Session(token, user.getId(), clock.instant().plus(Duration.ofDays(sessionDays))));
		return new LoginResponse(token, user.getId(), user.getNickname(), user.getAvatarUrl());
	}
}
