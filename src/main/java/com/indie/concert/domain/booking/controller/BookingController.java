package com.indie.concert.domain.booking.controller;

import com.indie.concert.domain.booking.dto.request.BookingConfirmRequest;
import com.indie.concert.domain.booking.dto.request.BookingCreateRequest;
import com.indie.concert.domain.booking.dto.response.BookingCreated;
import com.indie.concert.domain.booking.dto.response.BookingDetail;
import com.indie.concert.domain.booking.dto.response.BookingPaid;
import com.indie.concert.domain.booking.dto.response.BookingSummary;
import com.indie.concert.domain.booking.dto.response.EntryQr;
import com.indie.concert.domain.booking.service.BookingService;
import com.indie.concert.domain.booking.service.EntryService;
import com.indie.concert.global.auth.CurrentUser;
import com.indie.concert.global.common.response.ApiResponse;
import com.indie.concert.global.common.response.CursorPage;
import com.indie.concert.global.common.util.Cursor;
import com.indie.concert.global.common.util.PageSize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 프론트 -> Gateway -> Concert 예매 생성, 결제 승인, 조회, 취소와 입장 QR API. */
@RestController
public class BookingController {

    private final BookingService bookingService;
    private final EntryService entryService;
    private final PageSize pageSize;

    public BookingController(BookingService bookingService, EntryService entryService, PageSize pageSize) {
        this.bookingService = bookingService;
        this.entryService = entryService;
        this.pageSize = pageSize;
    }

    /**
     * N10 좌석 선점 - seatIds 로 여러 좌석 (공연의 maxSeatsPerUser 까지). 요청에 금액 필드가 없습니다. 금액은 좌석 가격의 합으로 서버가 정합니다 (77A).
     * 하나라도 남이 잡았으면 전부 안 잡히고 409 SEAT_TAKEN (result.seatId), 내 선점이 다른 좌석에 있으면 409 HOLD_EXISTS (result.bookingId - N13 으로 취소한 뒤 다시)
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param concertId 공연 ID
     * @param entryToken 대기열 통과 후 발급된 입장 토큰
     * @param request 선점할 좌석 목록
     */
    @PostMapping("/api/concerts/{concertId}/bookings")
    public ApiResponse<BookingCreated> create(@CurrentUser Long userId, @PathVariable @Positive long concertId,
                                         @RequestHeader(value = "X-Entry-Token", required = false) String entryToken,
                                         @Valid @RequestBody BookingCreateRequest request) {
        return ApiResponse.onSuccess(bookingService.create(concertId, userId, entryToken, request.seatIds()));
    }

    /**
     * N16 - 결제 승인. 티켓은 P1(/api/payments/confirm)이 아니라 여기로 - 내 선점이 살아 있어야 승인합니다.
     * 선점 마감이 지났으면 410 HOLD_EXPIRED (토스 호출 없음, 돈 안 나감), 승인 응답이 없으면 503 PAYMENT_PENDING.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param bookingId 예매 ID
     * @param request PG 결제 키와 요청 금액
     * @param forceDecline 로컬 테스트용 PG 강제 거절 코드
     */
    @PostMapping("/api/concerts/bookings/{bookingId}/confirm")
    public ApiResponse<BookingPaid> confirm(@CurrentUser Long userId, @PathVariable @Positive long bookingId,
                                            @Valid @RequestBody BookingConfirmRequest request,
                                            @RequestHeader(value = "X-Force-Decline", required = false) String forceDecline) {
        return ApiResponse.onSuccess(bookingService.pay(userId, bookingId, request.paymentKey(), request.amount(), forceDecline));
    }

    /**
     * 예매 목록을 조회합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param cursor 다음 조회 시작점을 나타내는 커서
     * @param size 페이지당 조회 개수
     */
    @GetMapping("/api/concerts/bookings")
    public ApiResponse<CursorPage<BookingSummary>> list(@CurrentUser Long userId,
                                                          @RequestParam(required = false) String cursor,
                                                          @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(bookingService.list(userId, Cursor.decode(cursor), pageSize.resolve(size)));
    }

    /**
     * 예매 상세를 조회합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param bookingId 예매 ID
     */
    @GetMapping("/api/concerts/bookings/{bookingId}")
    public ApiResponse<BookingDetail> get(@CurrentUser Long userId, @PathVariable @Positive long bookingId) {
        return ApiResponse.onSuccess(bookingService.get(userId, bookingId));
    }

    /**
     * 예매 취소를 요청하고 결과를 반환합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param bookingId 예매 ID
     */
    @PostMapping("/api/concerts/bookings/{bookingId}/cancel")
    public ApiResponse<BookingDetail> cancel(@CurrentUser Long userId, @PathVariable @Positive long bookingId) {
        return ApiResponse.onSuccess(bookingService.cancel(userId, bookingId));
    }

    /**
     * 예매한 회차의 입장용 QR을 발급합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param bookingId 예매 ID
     */
    @GetMapping("/api/concerts/bookings/{bookingId}/entry-qr")
    public ApiResponse<EntryQr> entryQr(@CurrentUser Long userId, @PathVariable @Positive long bookingId) {
        return ApiResponse.onSuccess(entryService.issue(userId, bookingId));
    }
}
