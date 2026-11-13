package com.indie.concert.domain.seat.repository;

import com.indie.concert.domain.seat.entity.ConcertSeatEntity;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * 공연 좌석 저장소 포트 — 구현은 ConcertSeatJpaRepository.
 * 상태 전이는 전부 조건부 UPDATE 이고 결과(0/1)로 판단합니다 — 동시에 결제해도 같은 좌석이 두 번 팔리지 않습니다.
 */
public interface ConcertSeatRepository {

    List<ConcertSeatEntity> findAllById(Iterable<Long> ids);

    /**
     * ★ 좌석 선점 — 빈 좌석이거나 마감이 지난 선점(HELD)일 때만. 마감은 예매의 hold_expires_at 을 그대로 씁니다.
     * 1 = 내가 잡음 · 0 = 살아 있는 선점 · 결제 중(PAYING) · 판매(SOLD). 동시에 와도 행 잠금 뒤 조건을 다시 봐서 한 명만 1 (11/04 77A)
     */
    int hold(long concertSeatId, long concertId, long bookingId, long userId);

    /** ★ 결제 시작 — 내 선점이고 서버 마감(DB now()) 전인 좌석만 HELD → PAYING. 반환이 예매 좌석 수보다 적으면 마감 지남 · 남이 가져감 · 이미 결제 중 */
    int startPaying(long bookingId, long userId);

    /** 승인 전 거부 · 거절 — 돈이 나가지 않았으니 PAYING → HELD (마감은 그대로 — 이미 지났으면 다음 사람이 바로 가져갈 수 있음) */
    int stopPaying(long bookingId);

    /** 결제 승인 → SOLD (PAYING 일 때만) */
    int sell(long bookingId);

    /** 예매 취소 · 만료 → AVAILABLE (이 예매가 잡은 좌석만 — 다음 사람에게 넘어간 좌석은 그대로) */
    int releaseBooking(long bookingId);

    /** DB 시계 (epoch ms) — 선점 시간 비교는 전부 DB now() 기준입니다. 앱 서버 시계는 쓰지 않습니다 (77A) */
    long nowMillis();

    /** 좌석 화면 — 이 공연에서 파는 좌석 (위치 + 가격 + 화면 상태). 상태는 DB now() 로 계산합니다 */
    List<SeatMapRow> findSeatMap(long concertId);

    /** 예매 상세 · 검표 — 공연 좌석의 층 · 구역 · 열 · 번호 · 가격 (층 · 구역 · 열 · 번호 순) */
    List<SeatRefRow> findRefs(Collection<Long> concertSeatIds);

    /** 관리자 가격 저장 전 확인 — seatIds 중 이 공연장 배치에 있는 좌석 수 */
    long countVenueSeats(long venueId, Long[] seatIds);

    /**
     * 관리자 가격 저장 — 공연장 좌석(seatIds)을 이 공연 판매 좌석으로 만들고, 이미 있으면 가격만 바꿉니다.
     * 선점 · 결제 중 · 판매된 좌석은 가격을 바꾸지 않습니다 (예매 금액이 어긋나지 않게). 반환 = 만들거나 바꾼 수
     */
    int upsertPrices(long concertId, Long[] seatIds, Integer[] prices);

    /**
     * 화면 상태 — AVAILABLE(선택 가능 · 마감 지난 선점 포함) · HELD(선점 · 결제 중) · SOLD.
     * 숫자 컬럼은 PostgreSQL numeric → BigDecimal 입니다.
     */
    interface SeatMapRow {
        Long getId();
        Long getSectionId();
        Integer getRowNo();
        Integer getSeatNo();
        BigDecimal getX();
        BigDecimal getY();
        BigDecimal getAngle();
        Integer getPrice();
        String getStatus();
    }

    interface SeatRefRow {
        Long getId();
        String getFloor();
        String getSection();
        Integer getRowNo();
        Integer getSeatNo();
        Integer getPrice();
    }
}
