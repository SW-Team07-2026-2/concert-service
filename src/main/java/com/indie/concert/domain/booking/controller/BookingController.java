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

/** 공개 API N10 ~ N13 · N16 · N17 — 예매 · 입장 QR. 모두 로그인 필요 (Gateway 가 X-User-Id 를 붙임 · POST 는 CSRF 도) */
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

    /** 좌석 고르기(선택완료) — 🔴 요청에 금액 필드가 없습니다. 금액은 좌석 가격으로 서버가 정합니다. 좌석을 잡지는 않습니다 */
    @PostMapping("/api/concerts/{id}/bookings")
    public ApiResponse<BookingCreated> create(@CurrentUser Long userId, @PathVariable @Positive long id,
                                         @RequestHeader(value = "X-Entry-Token", required = false) String entryToken,
                                         @Valid @RequestBody BookingCreateRequest request) {
        return ApiResponse.onSuccess(bookingService.create(id, userId, entryToken, request.seatId()));
    }

    /**
     * N16 — 결제 승인. 🔴 티켓은 P1(/api/payments/confirm)이 아니라 여기로 — 좌석을 먼저 확보해야 승인합니다.
     * 같은 좌석을 다른 사람이 먼저 결제했으면 409 SEAT_UNAVAILABLE ("이미 선택된 좌석입니다") · 돈은 나가지 않습니다.
     */
    @PostMapping("/api/concerts/bookings/{id}/confirm")
    public ApiResponse<BookingPaid> confirm(@CurrentUser Long userId, @PathVariable @Positive long id,
                                            @Valid @RequestBody BookingConfirmRequest request,
                                            @RequestHeader(value = "X-Force-Decline", required = false) String forceDecline) {
        return ApiResponse.onSuccess(bookingService.pay(userId, id, request.paymentKey(), request.amount(), forceDecline));
    }

    @GetMapping("/api/concerts/bookings")
    public ApiResponse<CursorPage<BookingSummary>> list(@CurrentUser Long userId,
                                                          @RequestParam(required = false) String cursor,
                                                          @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(bookingService.list(userId, Cursor.decode(cursor), pageSize.resolve(size)));
    }

    @GetMapping("/api/concerts/bookings/{id}")
    public ApiResponse<BookingDetail> get(@CurrentUser Long userId, @PathVariable @Positive long id) {
        return ApiResponse.onSuccess(bookingService.get(userId, id));
    }

    @PostMapping("/api/concerts/bookings/{id}/cancel")
    public ApiResponse<BookingDetail> cancel(@CurrentUser Long userId, @PathVariable @Positive long id) {
        return ApiResponse.onSuccess(bookingService.cancel(userId, id));
    }

    /** N17 — 입장 QR. 30초 뒤 무효라 앱이 20초마다 다시 부릅니다 (캡처본은 검표에서 ENTRY_QR_EXPIRED) */
    @GetMapping("/api/concerts/bookings/{id}/entry-qr")
    public ApiResponse<EntryQr> entryQr(@CurrentUser Long userId, @PathVariable @Positive long id) {
        return ApiResponse.onSuccess(entryService.issue(userId, id));
    }
}
