package com.indie.concert.domain.seat.repository;

import com.indie.concert.domain.seat.entity.SeatEntity;

import java.time.OffsetDateTime;
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
     * ★ 결제 승인 직전 좌석 확보 — 빈 좌석이고 예매가 아직 PENDING 일 때만. 1 = 이 예매가 승인 진행 · 0 = 먼저 결제한(또는 승인 중인) 사람이 있음.
     * 승인 중 좌석(HELD)은 시간이 지나도 여기서 가져가지 못합니다 — 결제 결과를 확인한 만료 처리기만 풉니다
     */
    int claim(long seatId, long bookingId, long userId, OffsetDateTime until);

    /** 승인 전 거부(금액 위조 · 결제 만료 등) — 돈이 나가지 않았으니 좌석을 다시 경합으로 */
    int unclaim(long bookingId);

    /** 결제 승인 → SOLD */
    int sell(long bookingId);

    /** 예매 취소 · 만료 · 결제 실패 → AVAILABLE (HELD · SOLD 모두) */
    int releaseBooking(long bookingId);

    /** 구역 하나를 rows × seatsPerRow 로 생성. 이미 있는 자리는 건너뜁니다 — 반환은 새로 만든 수 */
    int createGrid(long concertId, String section, int rows, int seatsPerRow, int price);
}
