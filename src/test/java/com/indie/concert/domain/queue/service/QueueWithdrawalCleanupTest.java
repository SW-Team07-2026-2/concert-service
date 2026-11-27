package com.indie.concert.domain.queue.service;

import com.indie.concert.domain.queue.repository.QueueStore;
import com.indie.concert.domain.queue.repository.QueueEntryJpaRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class QueueWithdrawalCleanupTest {
    @Test
    void deletesDatabaseOnlyAfterRedisCleanup() {
        var store = mock(QueueStore.class);
        var entries = mock(QueueEntryJpaRepository.class);
        new QueueWithdrawalCleanup(store, entries).remove(7);
        var order = inOrder(store, entries);
        order.verify(store).removeUser(7);
        order.verify(entries).deleteForUser(7);
    }

    @Test
    void failedRedisCleanupIsRetriedWithoutLosingDatabaseSnapshot() {
        var store = mock(QueueStore.class);
        var entries = mock(QueueEntryJpaRepository.class);
        doThrow(new IllegalStateException("down")).doNothing().when(store).removeUser(7);
        var cleanup = new QueueWithdrawalCleanup(store, entries);
        assertThatThrownBy(() -> cleanup.remove(7)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(entries);
        cleanup.remove(7);
        verify(entries).deleteForUser(7);
    }
}
