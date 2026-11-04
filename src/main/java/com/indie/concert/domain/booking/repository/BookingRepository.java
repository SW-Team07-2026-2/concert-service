package com.indie.concert.domain.booking.repository;

import com.indie.concert.domain.booking.entity.BookingEntity;
import org.springframework.data.domain.Limit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 예매 저장소 포트 — 구현은 BookingJpaRepository.
 * 상태 전이는 조건부 UPDATE 로만 합니다 — 중복 이벤트 · 동시 취소에도 한 번만 전이되고, 결과(0/1)로 판단합니다.
 */
public interface BookingRepository {

    Optional<BookingEntity> findById(Long id);

    /** ✅ 소유권은 항상 조회 조건에 — id 만으로 꺼내면 남의 예매가 보입니다 (IDOR) */
    Optional<BookingEntity> findByIdAndUserId(Long id, Long userId);

    List<BookingEntity> findByUserIdOrderByIdDesc(Long userId, Limit limit);

    List<BookingEntity> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long id, Limit limit);

    /** 이 공연에서 진행 중 · 확정된 내 예매 (1인 1석) */
    Optional<BookingEntity> findActive(long concertId, long userId);

    BookingEntity save(BookingEntity booking);

    int attachPgOrderId(long id, String pgOrderId);

    /** PENDING → CONFIRMED. 0 = 이미 전이됨 */
    int confirm(long id);

    /** PENDING · CONFIRMED → CANCELLED. 1 일 때만 좌석을 풉니다 — 두 번 풀리지 않게 */
    int cancel(long id);

    /** PENDING → EXPIRED */
    int expire(long id);

    /** 입장 — CONFIRMED 이고 아직 입장 전일 때만 기록. 0 = 이미 입장했거나 취소됨 (같은 QR 을 동시에 두 번 찍어도 한 번만) */
    int checkIn(long id, long checkerId, OffsetDateTime at);

    /** 정리할 결제 대기 예매 — 고른 지 selectedBefore 이전이고 승인 중이 아닌 것 · 승인 응답을 못 받은 채 claim 시간이 지난 것 */
    List<Long> findExpiredPending(OffsetDateTime now, OffsetDateTime selectedBefore, int limit);

    /** 경합 표시 — 이 공연에서 결제 대기 예매가 걸린 좌석 (createdAfter 이후에 고른 것만 · 만료된 선택 제외) */
    List<Long> findPendingSeatIds(long concertId, OffsetDateTime createdAfter);
}
