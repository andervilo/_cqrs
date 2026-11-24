package com.cqrs.orderquery.application.inbox;

import com.cqrs.orderquery.infrastructure.persistence.*;
import com.cqrs.orderquery.infrastructure.config.ProjectionConsumerProperties;
import com.cqrs.orderquery.domain.ProjectionOperation;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ProjectionInboxTransaction {
    public enum Outcome { APPLIED, DUPLICATE, IGNORED_STALE }
    private final ProjectionStateRepository states;
    private final InboxEventRepository inbox;
    private final ProjectionHandlerRegistry handlers;
    private final String consumer;
    public ProjectionInboxTransaction(ProjectionStateRepository states,InboxEventRepository inbox,
            ProjectionHandlerRegistry handlers,ProjectionConsumerProperties properties) {
        this.states=states;this.inbox=inbox;this.handlers=handlers;this.consumer=properties.consumerName();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Outcome apply(DecodedProjectionEvent e) {
        var state=states.lock(consumer,e);
        var seen=inbox.byEventId(consumer,e);
        if(seen.isPresent()) { validate(seen.get(),e); return Outcome.DUPLICATE; }
        var revision=inbox.byRevision(consumer,e);
        if(revision.isPresent()) { validate(revision.get(),e); return Outcome.DUPLICATE; }
        if(e.projectionVersion()==state.version()) {
            if(!e.eventId().equals(state.eventId()) || !Arrays.equals(e.contentHash(),state.contentHash()))
                throw new InvalidProjectionEvent("Conflicting identity or content for applied revision");
            inbox.insert(consumer,e,"APPLIED");
            return Outcome.DUPLICATE;
        }
        if(e.projectionVersion()<state.version()) {
            inbox.insert(consumer,e,"IGNORED_STALE");
            return Outcome.IGNORED_STALE;
        }
        var handler=handlers.require(e.eventType());
        if(e.operation()==ProjectionOperation.DELETE) handler.delete(e.projectionKey());
        else handler.upsert(e.snapshot(),e.projectionVersion());
        states.applied(consumer,e);
        inbox.insert(consumer,e,"APPLIED");
        return Outcome.APPLIED;
    }
    private void validate(InboxEventRepository.Seen seen,DecodedProjectionEvent e) {
        if(!seen.id().equals(e.eventId()) || !Arrays.equals(seen.hash(),e.contentHash()))
            throw new InvalidProjectionEvent("Reused event identity or revision with conflicting contract");
    }
}
