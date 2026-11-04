package com.indie.concert.domain.booking.dto.event;

import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.global.event.EventPublisher;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * booking.completed — 결제 승인으로 예매가 확정된 커밋에서 (FR-CON-16 · 10/24 concert.booked 에서 이름 단일화).
 * Member 는 본인 알림(target_user_id)으로만 적재하고, AI 는 선호 신호로 씁니다.
 */
@Component
public class BookingEvents {

    private final EventPublisher publisher;

    public BookingEvents(EventPublisher publisher) {
        this.publisher = publisher;
    }

    public void bookingCompleted(BookingEntity booking, ConcertEntity concert, int seatCount, OffsetDateTime bookedAt) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("bookingId", booking.getId());
        p.put("concertId", concert.getId());
        p.put("artistId", concert.getArtistId());
        p.put("userId", booking.getUserId());
        p.put("title", concert.getTitle());
        p.put("startsAt", concert.getStartsAt().toString());
        p.put("seatCount", seatCount);
        p.put("amount", booking.getTotalPrice());
        p.put("bookedAt", bookedAt.toString());
        publisher.publish("booking.completed", p);
    }
}
