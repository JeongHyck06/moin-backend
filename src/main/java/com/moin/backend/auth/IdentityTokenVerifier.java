package com.moin.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 공급자 공개키로 서명·발급자·대상·만료를 검증, Apple nonce는 5분 내 한 번만 사용 */
@Service
public class IdentityTokenVerifier {
    private final NimbusJwtDecoder google;
    private final NimbusJwtDecoder apple;
    private final boolean googleConfigured;
    private final Clock clock;
    private final Map<String, Instant> challenges = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public IdentityTokenVerifier(@Value("${moin.auth.google-client-ids:}") String googleIds,
            @Value("${moin.auth.apple-client-ids:com.moin}") String appleIds, Clock clock) {
        this.clock = clock;
        googleConfigured = !audiences(googleIds).isEmpty();
        google = decoder("https://www.googleapis.com/oauth2/v3/certs", audiences(googleIds),
                Set.of("https://accounts.google.com", "accounts.google.com"), clock);
        apple = decoder("https://appleid.apple.com/auth/keys", audiences(appleIds),
                Set.of("https://appleid.apple.com"), clock);
    }

    static Set<String> audiences(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    static NimbusJwtDecoder decoder(String jwks, Set<String> audiences, Set<String> issuers, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        validate(decoder, audiences, issuers, clock);
        return decoder;
    }

    /** 로컬 공개키 테스트도 운영과 같은 claim 검증기를 사용 */
    static void validate(NimbusJwtDecoder decoder, Set<String> audiences, Set<String> issuers, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, jwt -> {
            String issuer = jwt.getClaimAsString("iss");
            boolean valid = issuer != null && issuers.contains(issuer)
                    && jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(audiences::contains)
                    && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                    && !jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(30))
                    && jwt.getSubject() != null && !jwt.getSubject().isBlank() && jwt.getSubject().length() <= 200;
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }));
    }

    public Jwt google(String token) {
        if (!googleConfigured) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google 로그인을 준비 중이에요");
        return decode(google, token);
    }

    public synchronized String challenge() {
        Instant now = clock.instant();
        challenges.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        if (challenges.size() >= 2000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        challenges.put(nonce, now.plusSeconds(300));
        return nonce;
    }

    public Jwt apple(String token, String nonce) {
        Jwt jwt = decode(apple, token);
        consumeNonce(jwt, nonce);
        return jwt;
    }

    void consumeNonce(Jwt jwt, String nonce) {
        String claim = jwt.getClaimAsString("nonce");
        if (claim == null || !MessageDigest.isEqual(sha256(nonce).getBytes(StandardCharsets.UTF_8), claim.getBytes(StandardCharsets.UTF_8))) {
            throw unauthorized();
        }
        Instant expires = challenges.remove(nonce);
        if (expires == null || !expires.isAfter(clock.instant())) throw unauthorized();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private Jwt decode(NimbusJwtDecoder decoder, String token) {
        try { return decoder.decode(token); }
        catch (JwtException | IllegalArgumentException e) { throw unauthorized(); }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 인증에 실패했어요. 다시 시도해 주세요");
    }
}
