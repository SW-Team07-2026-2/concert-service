package com.indie.concert.domain.booking.service;

import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.config.ConcertProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 입장 QR — 유효 시간(30초) · 입장 마감을 넘지 않음 · 만료는 부르는 쪽이 판단 (11/27), 위조 · 다른 키 · 다른 종류 토큰은 INVALID */
class EntryQrTokensTest {

    static final String SECRET = "entry-qr-test-secret-0123456789abcdef";
    static final ConcertProperties PROPS = new ConcertProperties(null, null, null, null, null, null, null, null, null, null,
            new ConcertProperties.Entry(30, 60, 30));
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant FAR = NOW.plusSeconds(3600);

    private final EntryQrTokens tokens = at(NOW);

    @Test
    void issuedTokenLasts30Seconds() {
        EntryQrTokens.Issued qr = tokens.issue(30L, 42L, FAR);

        assertThat(qr.expiresAt()).isEqualTo(NOW.plusSeconds(30));
        EntryQrTokens.Claims claims = at(NOW.plusSeconds(29)).parse(qr.token());
        assertThat(claims).isEqualTo(new EntryQrTokens.Claims(30L, 42L, NOW.plusSeconds(30)));
        assertThat(claims.expired(NOW.plusSeconds(29))).isFalse();
    }

    /** ★ 캡처본 — 서명은 맞지만 30초가 지났음 (만료 판단은 검표가 시간 검사 뒤에) */
    @Test
    void screenshotExpiresAfter30Seconds() {
        String token = tokens.issue(30L, 42L, FAR).token();

        assertThat(tokens.parse(token).expired(NOW.plusSeconds(30))).isTrue();
    }

    /** 11/27 입장 마감 10초 전에 받은 QR 은 마감까지만 */
    @Test
    void tokenNeverOutlivesEntryClose() {
        EntryQrTokens.Issued qr = tokens.issue(30L, 42L, NOW.plusSeconds(10));

        assertThat(qr.expiresAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(tokens.parse(qr.token()).expiresAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void editedPayloadIsInvalid() {
        String[] parts = tokens.issue(30L, 42L, FAR).token().split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("\"bid\":30", "\"bid\":31")
                        .getBytes(StandardCharsets.UTF_8));

        assertCode(() -> tokens.parse(parts[0] + "." + forged + "." + parts[2]), "ENTRY_QR_INVALID");
    }

    /** 다른 키(예: 전 서비스 공유 시크릿)로 만든 토큰 · 같은 키라도 입장 QR 이 아닌 토큰 · 아무 문자열 */
    @Test
    void otherTokensAreInvalid() throws Exception {
        String otherKey = sign("internal-shared-secret-0123456789abcdef", "entry");
        String otherType = sign(SECRET, "SIGNUP");

        assertCode(() -> tokens.parse(otherKey), "ENTRY_QR_INVALID");
        assertCode(() -> tokens.parse(otherType), "ENTRY_QR_INVALID");
        assertCode(() -> tokens.parse("INDIE-TICKET:30"), "ENTRY_QR_INVALID");
    }

    private static String sign(String secret, String typ) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .claim("typ", typ).claim("bid", 30L).claim("cid", 42L).expirationTime(Date.from(NOW.plusSeconds(30))).build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    static EntryQrTokens at(Instant now) {
        try {
            return new EntryQrTokens(SECRET, PROPS, Clock.fixed(now, ZoneOffset.UTC));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(GeneralException.class)
                .extracting(e -> ((GeneralException) e).getErrorCode().getCode()).isEqualTo(code);
    }
}
