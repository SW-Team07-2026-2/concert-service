package com.indie.concert.global.auth;

import com.indie.concert.global.config.ConcertProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 나가는 X-Internal-Token (HS256 · 공유 시크릿) — {iss: concert, aud: <대상>, exp: now+300}. 매 호출마다 새로 만듭니다.
 * 토큰을 캐시하지 마세요 — 생성 비용은 무시할 수준이고, 캐시하면 만료 처리가 또 하나의 버그 지점이 됩니다.
 */
@Component
public class InternalTokenIssuer {

    private final MACSigner signer;
    private final ConcertProperties.InternalToken props;
    private final Clock clock;

    public InternalTokenIssuer(@Value("${internal.shared-secret}") String sharedSecret,
                               ConcertProperties props, Clock clock) throws JOSEException {
        this.signer = new MACSigner(sharedSecret.trim().getBytes(StandardCharsets.UTF_8));   // 256bit 미만이면 기동 실패
        this.props = props.internalToken();
        this.clock = clock;
    }

    public String issue(String audience) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(props.issuer())
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(props.ttlSeconds())))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("internal token signing failed", e);
        }
        return jwt.serialize();
    }
}
