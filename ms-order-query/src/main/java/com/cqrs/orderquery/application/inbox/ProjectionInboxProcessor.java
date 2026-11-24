package com.cqrs.orderquery.application.inbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ProjectionInboxProcessor {
    private final ProjectionInboxTransaction transaction;
    private final MeterRegistry metrics;
    public ProjectionInboxProcessor(ProjectionInboxTransaction transaction,MeterRegistry metrics) {
        this.transaction=transaction;this.metrics=metrics;
    }
    public ProjectionInboxTransaction.Outcome process(DecodedProjectionEvent event) {
        ProjectionInboxTransaction.Outcome result;
        try { result=transaction.apply(event); }
        catch(DuplicateKeyException conflict) {
            // A cross-key identity collision rolled back; classify it using a fresh transaction.
            result=transaction.apply(event);
        }
        metrics.counter("projection.inbox.processed","outcome",result.name()).increment();
        return result;
    }
}
