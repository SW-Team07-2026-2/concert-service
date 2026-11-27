package com.indie.concert.domain.booking.dto.event;

import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.global.event.EventPublisher;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concert 예매 확정과 환불 -> Outbox -> Kafka -> Member 알림과 AI 선호 신호.
 * 단순 선점 취소나 환불 없는 만료는 발행하지 않습니다. 시작 시각은 예매한 회차 기준입니다.
 */
@Component
public class BookingEvents {

    private final EventPublisher publisher;

    public BookingEvents(EventPublisher publisher) {
        this.publisher = publisher;
    }

    public void bookingCompleted(BookingEntity booking, ConcertEntity concert, OffsetDateTime sessionStartsAt, VenueEntity venue,
                                 List<Long> artistIds, int seatCount, OffsetDateTime bookedAt) {
        Map<String, Object> p = base(booking, concert, sessionStartsAt, venue);
        p.put("artistId", artistIds.isEmpty() ? null : artistIds.get(0));
        p.put("artistIds", artistIds);
        p.put("seatCount", seatCount);
        p.put("amount", booking.getTotalPrice());
        p.put("bookedAt", bookedAt.toString());
        publisher.publish("booking.completed", p);
    }

    public void bookingCancelled(BookingEntity booking, ConcertEntity concert, OffsetDateTime sessionStartsAt, VenueEntity venue,
                                 int seatCount, OffsetDateTime cancelledAt) {
        Map<String, Object> p = base(booking, concert, sessionStartsAt, venue);
        p.put("seatCount", seatCount);
        p.put("refundAmount", booking.getTotalPrice());
        p.put("cancelledAt", cancelledAt.toString());
        publisher.publish("booking.cancelled", p);
    }

    private static Map<String, Object> base(BookingEntity booking, ConcertEntity concert, OffsetDateTime sessionStartsAt,
                                            VenueEntity venue) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("bookingId", booking.getId());
        p.put("concertId", concert.getId());
        p.put("sessionId", booking.getSessionId());
        p.put("userId", booking.getUserId());
        p.put("title", concert.getTitle());
        p.put("startsAt", sessionStartsAt.toString());
        p.put("venueName", venue.getName());
        return p;
    }
}
