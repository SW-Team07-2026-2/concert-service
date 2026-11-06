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
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-local";

    private final InternalTokenFilter filter = new InternalTokenFilter(SECRET,
            new ConcertProperties.Internal("concert", List.of("studio", "ops"), 30, "gateway",
                    List.of("/internal/concerts", "/internal/concerts/ingest/diff")), Clock.systemUTC(), new ObjectMapper());

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

    @Test
    void unknownIssuerIs401() throws Exception {
        assertThat(call(token(SECRET, "gateway", "concert", 300)).getStatus()).isEqualTo(401);
    }

    /** 21A — 수집기가 공유 시크릿으로 직접 부르던 경로는 닫힘. 수집은 Gateway /ingest/** 가 발급한 iss=gateway 토큰만 */
    @Test
    void directIngestIssuerIs401() throws Exception {
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

    // ── 수집 경로 (docs/22 S1 · S2) — Gateway 가 수집 서명을 확인하고 발급한 토큰 ─────────────

    private static final String BODY = "{\"items\":[{\"source\":\"melon\",\"external_id\":\"a1\"}]}";

    @Test
    void gatewayIngestTokenWithSameBodyPassesAndBodyIsReadableAgain() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = ingestCall("/internal/concerts", ingestToken("ingest:crawler-2026-11", sha256(BODY)), BODY, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(BODY);
    }

    /** 가로챈 토큰을 다른 본문에 붙여도 통과하지 않음 */
    @Test
    void gatewayIngestTokenWithOtherBodyIs400() throws Exception {
        MockHttpServletResponse response = ingestCall("/internal/concerts", ingestToken("ingest:crawler-2026-11", sha256(BODY)),
                BODY.replace("a1", "a2"), new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("BODY_DIGEST_MISMATCH");
    }

    /** 수집 토큰으로 수집 API 가 아닌 내부 경로는 못 부름 */
    /** 1 MiB 를 넘는 본문은 해시 불일치가 아니라 413 */
    @Test
    void gatewayIngestBodyOver1MiBIs413() throws Exception {
        String big = "{\"items\":[\"" + "a".repeat(1024 * 1024) + "\"]}";
        MockHttpServletResponse response = ingestCall("/internal/concerts", ingestToken("ingest:crawler-2026-11", sha256(big)),
                big, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void gatewayIngestTokenOnOtherInternalPathIs401() throws Exception {
        assertThat(ingestCall("/internal/queue/rebuild", ingestToken("ingest:crawler-2026-11", sha256(BODY)), BODY, new MockFilterChain())
                .getStatus()).isEqualTo(401);
    }

    @Test
    void gatewayTokenWithoutIngestSubjectOrDigestIs401() throws Exception {
        assertThat(ingestCall("/internal/concerts", ingestToken(null, sha256(BODY)), BODY, new MockFilterChain()).getStatus()).isEqualTo(401);
        assertThat(ingestCall("/internal/concerts", ingestToken("ingest:crawler-2026-11", null), BODY, new MockFilterChain()).getStatus()).isEqualTo(401);
    }

    private MockHttpServletResponse ingestCall(String path, String token, String body, MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader(InternalTokenFilter.HEADER, token);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static String ingestToken(String sub, String bodySha256) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer("gateway").audience("concert")
                .expirationTime(Date.from(Instant.now().plusSeconds(60)));
        if (sub != null) claims.subject(sub);
        if (bodySha256 != null) claims.claim("body_sha256", bodySha256);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static String sha256(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    }
}
