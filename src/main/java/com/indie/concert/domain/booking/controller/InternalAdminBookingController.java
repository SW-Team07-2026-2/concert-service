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

/** Studio CS 관리자 -> Concert 내부 API로 회원 예매 조회와 취소 대행. */
@RestController
public class InternalAdminBookingController {

    private final BookingService bookingService;
    private final PageSize pageSize;

    public InternalAdminBookingController(BookingService bookingService, PageSize pageSize) {
        this.bookingService = bookingService;
        this.pageSize = pageSize;
    }

    /**
     * 회원의 예매 목록을 조회합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param cursor 다음 조회 시작점을 나타내는 커서
     * @param size 페이지당 조회 개수
     */
    @GetMapping("/internal/admin/concerts/users/{userId}/bookings")
    public ApiResponse<CursorPage<BookingSummary>> bookings(@PathVariable @Positive long userId,
                                                            @RequestParam(required = false) String cursor,
                                                            @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(bookingService.list(userId, Cursor.decode(cursor), pageSize.resolve(size)));
    }

    /**
     * 관리자가 회원을 대신해 예매를 취소합니다.
     *
     * @param bookingId 예매 ID
     */
    @PostMapping("/internal/admin/concerts/bookings/{bookingId}/cancel")
    public ApiResponse<BookingDetail> cancel(@PathVariable @Positive long bookingId) {
        return ApiResponse.onSuccess(bookingService.cancelByAdmin(bookingId));
    }
}
