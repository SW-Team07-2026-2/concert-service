package com.indie.concert.domain.booking.repository;

import com.indie.concert.domain.booking.entity.BookingEntity;
import org.springframework.data.domain.Limit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 예매 저장소 포트 - 구현은 BookingJpaRepository.
 * 상태 전이는 조건부 UPDATE 로만 합니다 - 중복 이벤트, 동시 취소에도 한 번만 전이되고, 결과(0/1)로 판단합니다.
 */
public interface BookingRepository {

    Optional<BookingEntity> findById(Long id);

    /** 소유권은 항상 조회 조건에 - id 만으로 꺼내면 남의 예매가 보입니다 (IDOR) */
    Optional<BookingEntity> findByIdAndUserId(Long id, Long userId);

    List<BookingEntity> findByUserIdOrderByIdDesc(Long userId, Limit limit);

    List<BookingEntity> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long id, Limit limit);

    /** 이 공연에서 진행 중, 확정된 내 예매 - 회차마다 1건 (그 안에 좌석 N개, idx_booking_concert) */
    List<BookingEntity> findActive(long concertId, long userId);

    BookingEntity save(BookingEntity booking);

    /** null 이면 떼어냄 - 거절 뒤 새 결제를 준비하기 전 (다시 부르면 새로 준비하게, 80B) */
    int attachPgOrderId(long id, String pgOrderId);

    /** 선점 서버 마감 = DB now() + seconds (N10, 같은 트랜잭션에서 좌석 선점 전에) */
    int startHold(long id, long seconds);

    /** 결제 승인 시작, 끝 - paying_at = now() / NULL */
    int markPaying(long id);

    int clearPaying(long id);

    /** 취소 전에 예매 행을 잠그고 지금 상태를 봅니다 - 확정 예매 취소(환불)일 때만 booking.cancelled 를 내기 위해. 없으면 null */
    String lockStatus(long id);

    /** 공연 변경 공지 알림 - 그 공연의 확정 예매자 (idx_booking_concert) */
    List<Long> findConfirmedUserIds(long concertId);

    /** PENDING -> CONFIRMED. 0 = 이미 전이됨 */
    int confirm(long id);

    /** PENDING, CONFIRMED -> CANCELLED. 1 일 때만 좌석을 풉니다 - 두 번 풀리지 않게 */
    int cancel(long id);

    /** PENDING -> EXPIRED */
    int expire(long id);

    /** 입장 - CONFIRMED 이고 아직 입장 전, 탈퇴 정리 전일 때만 기록. 0 = 이미 입장했거나 취소, 탈퇴됨 (같은 QR 을 동시에 두 번 찍어도 한 번만) */
    int checkIn(long id, long checkerId, OffsetDateTime at);

    /**
     * user.withdrawn - 탈퇴 회원의 진행 중, 확정 예매에 정리 표시. 입장한 예매는 건드리지 않습니다.
     * checkIn 과 같은 행을 조건부 UPDATE 로 바꾸므로 입장, 탈퇴가 동시에 와도 한쪽만 됩니다 (입장했으면 표시 안 함, 표시됐으면 입장 안 됨)
     */
    int markWithdrawn(long userId, OffsetDateTime at);

    /** 정리 표시가 있고 아직 닫히지 않은 이 회원의 예매 */
    List<Long> findWithdrawnIds(long userId);

    /** 탈퇴 정리 재시도 - 표시가 있고 아직 닫히지 않은 예매 (idx_booking_withdrawn, Payment 불통으로 남은 건) */
    List<Long> findWithdrawnOpen(int limit);

    /** 탈퇴, 회차 24시간 이내 - 환불 없이 CONFIRMED -> EXPIRED (TOS v3, 좌석은 SOLD 그대로 다시 팔지 않음) */
    int forfeit(long id);

    /** 회차를 지워도 되나 - 예매 기록(상태 무관)이 있으면 못 지웁니다 */
    boolean existsBySession(long sessionId);

    /** 정리할 선점 - 서버 마감(DB now())이 지났고 결제 중이 아닌 PENDING (idx_booking_hold) */
    List<Long> findExpiredHolds(int limit);

    /** 결제 승인 응답을 못 받은 채 seconds 가 지난 PENDING - Payment 에 결과를 묻습니다 (81A, 1분) */
    List<Long> findStalePaying(long seconds, int limit);
}
