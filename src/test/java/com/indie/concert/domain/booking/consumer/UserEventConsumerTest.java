package com.indie.concert.domain.booking.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.booking.service.BookingService;
import com.indie.concert.domain.queue.service.QueueWithdrawalCleanup;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserEventConsumerTest {
    @Test
    void queueCleanupFailureDoesNotAcknowledgeWithdrawal() throws Exception {
        var bookings = mock(BookingService.class);
        var queues = mock(QueueWithdrawalCleanup.class);
        var ack = mock(Acknowledgment.class);
        var consumer = new UserEventConsumer(bookings, new ObjectMapper(), queues);
        var event = new ConsumerRecord<String, String>("user.withdrawn", 0, 0, "7",
                "{\"eventId\":\"00000000-0000-0000-0000-000000000001\",\"payload\":{\"userId\":7}}");
        doThrow(new IllegalStateException("down")).doNothing().when(queues).remove(7);
        assertThatThrownBy(() -> consumer.onUserWithdrawn(event, ack)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ack);
        consumer.onUserWithdrawn(event, ack);
        verify(ack).acknowledge();
        verify(bookings, times(2)).releaseWithdrawn(7);
    }
}
