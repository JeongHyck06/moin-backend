package com.moin.backend.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.server.ResponseStatusException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

class IdentityTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    /** 서명·대상·발급자·만료·nonce 재사용이 사용자 세션 발급 전에 거절되는지 검증 */
    @Test
    void rejectsForgedOrMisaddressedTokensAndReplayedAppleChallenges() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).generate();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        IdentityTokenVerifier.validate(decoder, Set.of("com.moin"), Set.of("https://appleid.apple.com"), clock);
        assertEquals("apple-user", decoder.decode(token(key, "com.moin", "https://appleid.apple.com", NOW.plusSeconds(300))).getSubject());
        assertThrows(JwtException.class, () -> decoder.decode(token(key, "other-app", "https://appleid.apple.com", NOW.plusSeconds(300))));
        assertThrows(JwtException.class, () -> decoder.decode(token(key, "com.moin", "https://attacker.invalid", NOW.plusSeconds(300))));
        assertThrows(JwtException.class, () -> decoder.decode(token(key, "com.moin", "https://appleid.apple.com", NOW.minusSeconds(120))));
        RSAKey attacker = new RSAKeyGenerator(2048).generate();
        assertThrows(JwtException.class, () -> decoder.decode(token(attacker, "com.moin", "https://appleid.apple.com", NOW.plusSeconds(300))));
        IdentityTokenVerifier verifier = new IdentityTokenVerifier("", "com.moin", clock);
        assertThrows(ResponseStatusException.class, () -> verifier.google("not-a-token"));
        String nonce = verifier.challenge();
        Jwt jwt = Jwt.withTokenValue("verified").header("alg", "RS256").subject("apple-user")
                .claim("nonce", IdentityTokenVerifier.sha256(nonce)).build();
        assertThrows(ResponseStatusException.class, () -> verifier.consumeNonce(jwt, "wrong-nonce"));
        verifier.consumeNonce(jwt, nonce);
        assertThrows(ResponseStatusException.class, () -> verifier.consumeNonce(jwt, nonce));
    }

    private static String token(RSAKey key, String audience, String issuer, Instant expires) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer(issuer).audience(audience).subject("apple-user")
                .issueTime(Date.from(NOW.minusSeconds(180))).expirationTime(Date.from(expires)).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
