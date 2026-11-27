package com.indie.concert.domain.queue.service;

import com.indie.concert.domain.queue.repository.QueueEntryJpaRepository;
import com.indie.concert.domain.queue.repository.QueueStore;
import org.springframework.stereotype.Service;

/** Member -> Kafka user.withdrawn -> Redis 대기열과 복구용 DB 스냅샷 삭제. */
@Service
public class QueueWithdrawalCleanup {
    private final QueueStore store;
    private final QueueEntryJpaRepository entries;

    public QueueWithdrawalCleanup(QueueStore store, QueueEntryJpaRepository entries) {
        this.store = store;
        this.entries = entries;
    }

    /** Redis 실패 시 예외를 전달해 탈퇴 이벤트를 재시도합니다. 반복 삭제는 멱등입니다. */
    public void remove(long userId) {
        store.removeUser(userId);
        entries.deleteForUser(userId);
    }
}
