package com.indie.concert.domain.booking.controller;

import com.indie.concert.domain.booking.dto.response.BookingDetail;
import com.indie.concert.domain.booking.dto.response.BookingSummary;
import com.indie.concert.domain.booking.service.BookingService;
import com.indie.concert.global.common.response.ApiResponse;
import com.indie.concert.global.common.response.CursorPage;
import com.indie.concert.global.common.util.Cursor;
import com.indie.concert.global.common.util.PageSize;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 — CS 관리자(Studio)의 회원 예매 조회 · 취소 대행. X-Internal-Token(iss=studio) 필수.
 * 누가 했는지는 Studio 의 작업 기록에 남습니다.
 */
@RestController
public class InternalAdminBookingController {

    private final BookingService bookingService;
    private final PageSize pageSize;

    public InternalAdminBookingController(BookingService bookingService, PageSize pageSize) {
        this.bookingService = bookingService;
        this.pageSize = pageSize;
    }

    /** 회원의 예매 목록 — 최신순 커서 (내 예매 목록 N11 과 같은 모양) */
    @GetMapping("/internal/admin/concerts/users/{userId}/bookings")
    public ApiResponse<CursorPage<BookingSummary>> bookings(@PathVariable @Positive long userId,
                                                            @RequestParam(required = false) String cursor,
                                                            @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(bookingService.list(userId, Cursor.decode(cursor), pageSize.resolve(size)));
    }

    /**
     * 예매 취소 대행 — 회원 본인의 취소(N13)와 같은 규칙입니다 (입장한 예매 · 공연 24시간 전이 지난 예매는 409).
     * 본문 { reason } 은 Studio 의 작업 기록용이라 여기서는 읽지 않습니다.
     */
    @PostMapping("/internal/admin/concerts/bookings/{id}/cancel")
    public ApiResponse<BookingDetail> cancel(@PathVariable @Positive long id) {
        return ApiResponse.onSuccess(bookingService.cancelByAdmin(id));
    }
}
