package com.indie.concert.domain.booking.client;

import com.indie.concert.global.auth.InternalTokenFilter;
import com.indie.concert.global.auth.InternalTokenIssuer;
import com.indie.concert.global.common.filter.CorrelationIdFilter;
import com.indie.concert.global.common.response.ApiResponse;
import com.indie.concert.global.config.ConcertProperties;
import org.slf4j.MDC;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Concert → Payment 내부 호출 (X-Internal-Token · iss=concert · aud=payment). 타임아웃 3초 */
@Component
public class HttpPaymentClient implements PaymentClient {

    static final String ORDER_TYPE = "TICKET";

    private final RestClient rest;
    private final InternalTokenIssuer tokens;

    public HttpPaymentClient(ConcertProperties props, InternalTokenIssuer tokens) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.payment().connectTimeoutMs());
        factory.setReadTimeout(props.payment().readTimeoutMs());
        this.rest = RestClient.builder().baseUrl(props.payment().baseUrl()).requestFactory(factory).build();
        this.tokens = tokens;
    }

    @Override
    public Prepared prepare(long bookingId, long userId, int amount) {
        try {
            ApiResponse<Prepared> res = rest.post().uri("/internal/payments/prepare")
                    .headers(h -> {
                        h.set(InternalTokenFilter.HEADER, tokens.issue("payment"));
                        correlationId(h);
                    })
                    .body(new PrepareRequest(ORDER_TYPE, String.valueOf(bookingId), userId, amount))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<Prepared>>() {});
            if (res == null || res.result() == null) throw new RestClientException("empty prepare response");
            return res.result();
        } catch (RestClientException e) {
            throw new Unavailable("prepare", e);
        }
    }

    @Override
    public String cancel(long bookingId, String reason, boolean refundApproved) {
        try {
            return rest.post().uri("/internal/payments/cancel")
                    .headers(h -> {
                        h.set(InternalTokenFilter.HEADER, tokens.issue("payment"));
                        correlationId(h);
                    })
                    .body(new CancelRequest(ORDER_TYPE, String.valueOf(bookingId), reason, refundApproved))
                    .exchange((req, res) -> {
                        if (res.getStatusCode().value() == 404) return "NOT_FOUND";
                        if (res.getStatusCode().isError()) throw new RestClientException("status " + res.getStatusCode());
                        ApiResponse<Cancelled> body = res.bodyTo(new ParameterizedTypeReference<ApiResponse<Cancelled>>() {});
                        if (body == null || body.result() == null || body.result().status() == null) {
                            throw new RestClientException("empty cancel response");
                        }
                        return body.result().status();
                    });
        } catch (RestClientException e) {
            throw new Unavailable("cancel", e);
        }
    }

    @Override
    public Confirmed confirm(long bookingId, long userId, String paymentKey, int amount, String forceDecline) {
        try {
            return rest.post().uri("/internal/payments/confirm")
                    .headers(h -> {
                        h.set(InternalTokenFilter.HEADER, tokens.issue("payment"));
                        correlationId(h);
                    })
                    .body(new ConfirmRequest(ORDER_TYPE, String.valueOf(bookingId), userId, paymentKey, amount, forceDecline))
                    .exchange((req, res) -> {
                        if (res.getStatusCode().is4xxClientError()) {
                            ApiResponse<Void> error = res.bodyTo(new ParameterizedTypeReference<ApiResponse<Void>>() {});
                            if (error == null || error.code() == null) throw new RestClientException("empty confirm error");
                            throw new Rejected(HttpStatus.valueOf(res.getStatusCode().value()), error.code(), error.message());
                        }
                        if (res.getStatusCode().isError()) throw new RestClientException("status " + res.getStatusCode());
                        ApiResponse<Confirmed> body = res.bodyTo(new ParameterizedTypeReference<ApiResponse<Confirmed>>() {});
                        if (body == null || body.result() == null || body.result().status() == null) {
                            throw new RestClientException("empty confirm response");
                        }
                        return body.result();
                    });
        } catch (RestClientException e) {
            throw new Unavailable("confirm", e);
        }
    }

    private static void correlationId(HttpHeaders h) {
        String id = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (id != null) h.set(CorrelationIdFilter.HEADER, id);
    }

    record PrepareRequest(String orderType, String orderRef, long userId, int amount) {}

    record CancelRequest(String orderType, String orderRef, String reason, boolean refundApproved) {}

    record ConfirmRequest(String orderType, String orderRef, long userId, String paymentKey, int amount, String forceDecline) {}

    record Cancelled(String status) {}
}
