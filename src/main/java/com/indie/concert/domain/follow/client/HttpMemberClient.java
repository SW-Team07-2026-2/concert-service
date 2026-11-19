package com.indie.concert.domain.follow.client;

import com.indie.concert.global.auth.InternalTokenFilter;
import com.indie.concert.global.auth.InternalTokenIssuer;
import com.indie.concert.global.common.filter.CorrelationIdFilter;
import com.indie.concert.global.common.response.ApiResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/** Concert → Member 내부 호출 (X-Internal-Token · iss=concert · aud=member) — GET /internal/members/{userId}/follows · 타임아웃 1초 / 3초 */
@Component
public class HttpMemberClient implements MemberClient {

    private final RestClient rest;
    private final InternalTokenIssuer tokens;

    public HttpMemberClient(@Value("${app.member.base-url}") String baseUrl, InternalTokenIssuer tokens) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(3000);
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
    }

    @Override
    public List<FollowedArtist> follows(long userId) {
        try {
            ApiResponse<Items> res = rest.get().uri("/internal/members/{userId}/follows", userId)
                    .headers(h -> {
                        h.set(InternalTokenFilter.HEADER, tokens.issue("member"));
                        String id = MDC.get(CorrelationIdFilter.MDC_KEY);
                        if (id != null) h.set(CorrelationIdFilter.HEADER, id);
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<Items>>() {});
            if (res == null || res.result() == null || res.result().items() == null) throw new RestClientException("empty follows response");
            return res.result().items();
        } catch (RestClientException e) {
            throw new Unavailable(e);
        }
    }

    record Items(List<FollowedArtist> items) {}
}
