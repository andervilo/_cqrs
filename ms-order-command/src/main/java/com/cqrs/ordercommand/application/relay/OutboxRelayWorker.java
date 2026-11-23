package com.cqrs.ordercommand.application.relay;

import com.cqrs.ordercommand.infrastructure.persistence.OutboxRelayStore;
import com.cqrs.ordercommand.infrastructure.messaging.KafkaOutboxPublisher;
import io.micrometer.core.instrument.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class OutboxRelayWorker {
    public enum Result { EMPTY, SENT, FAILED }
    private static final Logger log=LoggerFactory.getLogger(OutboxRelayWorker.class);
    private final OutboxRelayStore store;
    private final KafkaOutboxPublisher publisher;
    private final OutboxRelayProperties properties;
    private final Counter published,failed,exhausted;
    public OutboxRelayWorker(OutboxRelayStore store,KafkaOutboxPublisher publisher,OutboxRelayProperties properties,MeterRegistry metrics) {
        this.store=store; this.publisher=publisher; this.properties=properties;
        published=metrics.counter("outbox.relay.published");
        failed=metrics.counter("outbox.relay.failed");
        exhausted=metrics.counter("outbox.relay.exhausted");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Result publishNext() {
        var next=store.lockNext(properties.maxAttempts());
        if(next.isEmpty()) return Result.EMPTY;
        var event=next.get();
        try {
            publisher.publish(event);
        } catch(RuntimeException error) {
            int attempts=event.attemptCount()+1;
            boolean terminal=attempts>=properties.maxAttempts();
            String errorType=rootErrorType(error);
            store.failed(event.id(),attempts,terminal?null:properties.backoffMillis(attempts),errorType);
            failed.increment();
            if(terminal) exhausted.increment();
            log.warn("Outbox publish failed: eventId={}, eventType={}, attempt={}, exhausted={}, error={}",
                    event.id(),event.eventType(),attempts,terminal,errorType);
            return Result.FAILED;
        }
        // Do not turn a database failure here into a publication failure: rollback and allow safe redelivery.
        store.sent(event.id());
        published.increment();
        return Result.SENT;
    }
    private String rootErrorType(Throwable error) {
        while(error.getCause()!=null && error.getCause()!=error) error=error.getCause();
        return error.getClass().getSimpleName();
    }
}
