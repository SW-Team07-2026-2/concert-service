package com.indie.concert.domain.seat.repository;

import com.indie.concert.domain.seat.entity.SeatEntity;

import java.util.List;
import java.util.Optional;

/**
 * 좌석 저장소 포트 — 구현은 SeatJpaRepository.
 * 상태 전이는 전부 조건부 UPDATE 이고 결과(0/1)로 판단합니다 — 동시에 결제해도 같은 좌석이 두 번 팔리지 않습니다.
 */
public interface SeatRepository {

    Optional<SeatEntity> findById(Long id);

    List<SeatEntity> findByConcertIdOrderById(Long concertId);

    /**
     * ★ N10 선점 — 빈 좌석이거나 마감이 지난 선점(HELD)일 때만. 마감은 예매의 hold_expires_at 을 그대로 씁니다.
     * 1 = 내가 잡음 · 0 = 살아 있는 선점 · 결제 중(PAYING) · 판매(SOLD). 동시에 와도 행 잠금 뒤 조건을 다시 봐서 한 명만 1 (11/04 77A)
     */
    int hold(long seatId, long concertId, long bookingId, long userId);

    /** ★ N16 결제 시작 — 내 선점이고 서버 마감(DB now()) 전일 때만 HELD → PAYING. 0 = 마감 지남 · 남이 가져감 · 이미 결제 중 */
    int startPaying(long bookingId, long userId);

    /** 승인 전 거부 · 거절 — 돈이 나가지 않았으니 PAYING → HELD (마감은 그대로 — 이미 지났으면 다음 사람이 바로 가져갈 수 있음) */
    int stopPaying(long bookingId);

    /** 결제 승인 → SOLD (PAYING 일 때만) */
    int sell(long bookingId);

    /** 예매 취소 · 만료 → AVAILABLE (이 예매가 잡은 좌석만 — 다음 사람에게 넘어간 좌석은 그대로) */
    int releaseBooking(long bookingId);

    /** DB 시계 (epoch ms) — 선점 시간 비교는 전부 DB now() 기준입니다. 앱 서버 시계는 쓰지 않습니다 (77A) */
    long nowMillis();

    /** 구역 하나를 rows × seatsPerRow 로 생성. 이미 있는 자리는 건너뜁니다 — 반환은 새로 만든 수 */
    int createGrid(long concertId, String section, int rows, int seatsPerRow, int price);
}
