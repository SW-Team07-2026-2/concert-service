package com.indie.concert.domain.booking.service;

import com.indie.concert.domain.booking.exception.BookingErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.config.ConcertProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

/**
 * 입장 QR 토큰 (HS256 · 30초) — Concert 만 아는 키(entry.qr-secret)로 서명합니다. 내부 호출 공유 시크릿과 다른 키 —
 * 다른 서비스가 털려도 입장권을 만들 수 없게.
 * 클레임은 예매 · 공연 id 와 만료뿐입니다 (QR 은 누구나 읽음 — 이름 · 좌석을 넣지 않음). 1회 입장은 DB(checked_in_at)가 보장합니다.
 */
@Component
public class EntryQrTokens {

    static final String TYPE = "entry";
    private static final Logger log = LoggerFactory.getLogger(EntryQrTokens.class);

    private final MACSigner signer;
    private final MACVerifier verifier;
    private final long ttlSeconds;
    private final Clock clock;

    public EntryQrTokens(@Value("${entry.qr-secret}") String secret, ConcertProperties props, Clock clock) throws JOSEException {
        byte[] key = secret.trim().getBytes(StandardCharsets.UTF_8);   // 32바이트 미만이면 MACSigner 가 기동을 막습니다
        this.signer = new MACSigner(key);
        this.verifier = new MACVerifier(key);
        this.ttlSeconds = props.entry().qrTtlSeconds();
        this.clock = clock;
    }

    public Issued issue(long bookingId, long concertId) {
        Instant exp = clock.instant().plusSeconds(ttlSeconds);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("typ", TYPE)
                .claim("bid", bookingId)
                .claim("cid", concertId)
                .expirationTime(Date.from(exp))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("entry qr signing failed", e);
        }
        return new Issued(jwt.serialize(), exp);
    }

    /** 서명 · typ 가 틀리면 ENTRY_QR_INVALID (어느 검사인지는 로그), 서명은 맞는데 시간이 지났으면 ENTRY_QR_EXPIRED (캡처본) */
    public Claims verify(String token) {
        Long bookingId;
        Long concertId;
        Date exp;
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) throw invalid("alg");   // ★ alg 고정
            if (!jwt.verify(verifier)) throw invalid("signature");
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            bookingId = c.getLongClaim("bid");
            concertId = c.getLongClaim("cid");
            exp = c.getExpirationTime();
            if (!TYPE.equals(c.getStringClaim("typ"))) throw invalid("typ");
            if (bookingId == null) throw invalid("bid");
            if (concertId == null) throw invalid("cid");
            if (exp == null) throw invalid("exp");
        } catch (ParseException | JOSEException e) {
            throw invalid("parse");
        }
        if (!exp.toInstant().isAfter(clock.instant())) {
            throw new GeneralException(BookingErrorCode.ENTRY_QR_EXPIRED);
        }
        return new Claims(bookingId, concertId);
    }

    /** 검표 화면에는 "입장 QR 이 아닙니다" 하나 — 위조 QR 에 어디서 걸렸는지 알려주지 않습니다 */
    private static GeneralException invalid(String reason) {
        log.warn("entry_qr_rejected reason={}", reason);
        return new GeneralException(BookingErrorCode.ENTRY_QR_INVALID);
    }

    public record Issued(String token, Instant expiresAt) {}

    public record Claims(long bookingId, long concertId) {}
}
