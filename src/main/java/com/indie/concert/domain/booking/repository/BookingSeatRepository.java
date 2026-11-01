package com.indie.concert.domain.booking.repository;

import com.indie.concert.domain.booking.entity.BookingSeatEntity;

import java.util.List;

/** 예매-좌석 저장소 포트 — 구현은 BookingSeatJpaRepository */
public interface BookingSeatRepository {

    List<BookingSeatEntity> findByBookingId(Long bookingId);

    List<BookingSeatEntity> findByBookingIdIn(List<Long> bookingIds);

    BookingSeatEntity save(BookingSeatEntity bookingSeat);
}
