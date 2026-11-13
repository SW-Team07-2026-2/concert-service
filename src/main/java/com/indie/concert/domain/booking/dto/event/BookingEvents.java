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
 * 예매 이벤트 — Member 는 본인 알림(userId)으로만 적재하고, AI 는 선호 신호로 씁니다.
 * <pre>
 * booking.completed  결제 승인으로 예매가 확정된 커밋에서
 * booking.cancelled  확정 예매를 취소(환불)한 커밋에서만 — 결제 전 선택 취소 · 시간 만료 · 탈퇴 정리는 내지 않습니다
 * </pre>
 * artistIds 는 라인업 순서 (첫 번째 = 헤드라이너) · artistId 는 헤드라이너 (artistIds 이전 구독자용).
 */
@Component
public class BookingEvents {

    private final EventPublisher publisher;

    public BookingEvents(EventPublisher publisher) {
        this.publisher = publisher;
    }

    public void bookingCompleted(BookingEntity booking, ConcertEntity concert, VenueEntity venue, List<Long> artistIds,
                                 int seatCount, OffsetDateTime bookedAt) {
        Map<String, Object> p = base(booking, concert, venue);
        p.put("artistId", artistIds.isEmpty() ? null : artistIds.get(0));
        p.put("artistIds", artistIds);
        p.put("seatCount", seatCount);
        p.put("amount", booking.getTotalPrice());
        p.put("bookedAt", bookedAt.toString());
        publisher.publish("booking.completed", p);
    }

    public void bookingCancelled(BookingEntity booking, ConcertEntity concert, VenueEntity venue, int seatCount,
                                 OffsetDateTime cancelledAt) {
        Map<String, Object> p = base(booking, concert, venue);
        p.put("seatCount", seatCount);
        p.put("refundAmount", booking.getTotalPrice());
        p.put("cancelledAt", cancelledAt.toString());
        publisher.publish("booking.cancelled", p);
    }

    private static Map<String, Object> base(BookingEntity booking, ConcertEntity concert, VenueEntity venue) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("bookingId", booking.getId());
        p.put("concertId", concert.getId());
        p.put("userId", booking.getUserId());
        p.put("title", concert.getTitle());
        p.put("startsAt", concert.getStartsAt().toString());
        p.put("venueName", venue.getName());
        return p;
    }
}
