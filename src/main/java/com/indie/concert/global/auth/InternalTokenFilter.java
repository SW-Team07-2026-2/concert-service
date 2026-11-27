package com.indie.concert.global.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.response.ApiResponse;
import com.indie.concert.global.config.ConcertProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 내부 토큰의 서명, 발급자, 대상과 만료 시각을 검증합니다.
 * Studio 관리자 경로와 Catalog의 아티스트 소식 조회 경로를 분리합니다.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";
    /** 팔로우 탭 업데이트순 - Catalog 가 부르는 경로 */
    public static final String NEWS_PATH = "/internal/concerts/artists/latest";
    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);

    private final MACVerifier verifier;
    private final ConcertProperties.Internal props;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public InternalTokenFilter(String sharedSecret, ConcertProperties.Internal props, Clock clock, ObjectMapper objectMapper) {
        try {
            this.verifier = new MACVerifier(sharedSecret.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("internal.shared-secret 은 32바이트 이상이어야 합니다", e);
        }
        this.props = props;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            reject(request, response, CommonErrorCode.INTERNAL_TOKEN_REQUIRED, "missing");
            return;
        }
        Verified verified = verify(token);
        if (verified.claims() == null) {
            reject(request, response, "expired".equals(verified.reason())
                    ? CommonErrorCode.INTERNAL_TOKEN_EXPIRED : CommonErrorCode.INTERNAL_TOKEN_INVALID, verified.reason());
            return;
        }
        List<String> issuers = NEWS_PATH.equals(request.getRequestURI()) ? props.newsIssuers() : props.allowedIssuers();
        if (!issuers.contains(verified.claims().getIssuer())) {
            reject(request, response, CommonErrorCode.INTERNAL_TOKEN_INVALID, "issuer");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 2 서명, alg, 3 aud, 4 exp - 통과하면 클레임, 아니면 거절 사유. iss 는 호출하는 쪽이 봅니다 */
    private Verified verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) return Verified.rejected("alg");   // alg 고정
            if (!jwt.verify(verifier)) return Verified.rejected("signature");
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getIssuer() == null) return Verified.rejected("issuer");
            if (claims.getAudience() == null || !claims.getAudience().contains(props.expectedAudience())) return Verified.rejected("audience");
            Date exp = claims.getExpirationTime();
            if (exp == null) return Verified.rejected("exp");
            Instant now = clock.instant();
            return now.isBefore(exp.toInstant().plusSeconds(props.clockSkewSeconds())) ? new Verified(claims, null) : Verified.rejected("expired");
        } catch (Exception e) {
            return Verified.rejected("parse");
        }
    }

    /** claims 가 null 이면 reason 이 거절 사유 (expired 만 응답 코드가 다르고 나머지는 로그로만 구분) */
    private record Verified(JWTClaimsSet claims, String reason) {
        static Verified rejected(String reason) {
            return new Verified(null, reason);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, CommonErrorCode code, String reason) throws IOException {
        log.warn("internal_token_rejected code={} reason={} path={} remote={}", code, reason, request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.onFailure(code, code.getMessage(), null));
    }
}
