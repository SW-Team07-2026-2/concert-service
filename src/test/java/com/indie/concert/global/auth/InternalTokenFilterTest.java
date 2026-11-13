package com.indie.concert.global.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.global.config.ConcertProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-local";

    private final InternalTokenFilter filter = new InternalTokenFilter(SECRET,
            new ConcertProperties.Internal("concert", List.of("studio", "ops"), 30), Clock.systemUTC(), new ObjectMapper());

    private MockHttpServletResponse call(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/x");
        if (token != null) request.addHeader(InternalTokenFilter.HEADER, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void missingTokenIs401() throws Exception {
        MockHttpServletResponse response = call(null);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INTERNAL_TOKEN_REQUIRED");
    }

    @Test
    void validTokenPasses() throws Exception {
        assertThat(call(token(SECRET, "studio", "concert", 300)).getStatus()).isEqualTo(200);
    }

    @Test
    void wrongSignatureIs401() throws Exception {
        assertThat(call(token("another-secret-another-secret-another", "studio", "concert", 300)).getStatus()).isEqualTo(401);
    }

    @Test
    void wrongAudienceIs401() throws Exception {
        assertThat(call(token(SECRET, "studio", "payment", 300)).getStatus()).isEqualTo(401);
    }

    /** 11/13 — 수집 경로를 없애 iss=gateway(수집) · ingest 토큰도 받지 않습니다. 공연은 관리자(studio)를 거쳐서만 */
    @Test
    void unknownIssuerIs401() throws Exception {
        assertThat(call(token(SECRET, "gateway", "concert", 300)).getStatus()).isEqualTo(401);
        assertThat(call(token(SECRET, "ingest", "concert", 300)).getStatus()).isEqualTo(401);
    }

    @Test
    void expiredBeyondSkewIs401() throws Exception {
        MockHttpServletResponse response = call(token(SECRET, "studio", "concert", -60));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INTERNAL_TOKEN_EXPIRED");
    }

    /** 만료 시각이 없으면 만료가 아니라 잘못된 토큰 */
    @Test
    void tokenWithoutExpIsInvalid() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder().issuer("studio").audience("concert").build());
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = call(jwt.serialize());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INTERNAL_TOKEN_INVALID");
    }

    private static String token(String secret, String iss, String aud, long expInSeconds) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer(iss).audience(aud).expirationTime(Date.from(Instant.now().plusSeconds(expInSeconds))).build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
