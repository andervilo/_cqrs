package com.cqrs.ordercommand.application.relay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;

@Component
@ConditionalOnProperty(prefix="outbox.relay",name="enabled",havingValue="true")
public class OutboxRelayScheduler {
    private static final Logger log=LoggerFactory.getLogger(OutboxRelayScheduler.class);
    private final OutboxRelayWorker worker;
    private final OutboxRelayProperties properties;
    public OutboxRelayScheduler(OutboxRelayWorker worker,OutboxRelayProperties properties) {
        this.worker=worker; this.properties=properties;
    }
    @Scheduled(fixedDelayString="${outbox.relay.poll-interval-ms}",initialDelayString="${outbox.relay.initial-delay-ms}")
    public void poll() {
        try {
            for(int i=0;i<properties.batchSize() && !Thread.currentThread().isInterrupted();i++) {
                if(worker.publishNext()!=OutboxRelayWorker.Result.SENT) break;
            }
        } catch(RuntimeException error) {
            log.error("Outbox polling failed: error={}",error.getClass().getSimpleName());
        }
    }
}
