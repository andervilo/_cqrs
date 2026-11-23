package com.cqrs.ordercommand.application.service;

import com.cqrs.ordercommand.application.dto.*;
import com.cqrs.ordercommand.application.exception.NotFoundException;
import com.cqrs.ordercommand.application.mapper.ApiMapper;
import com.cqrs.ordercommand.domain.model.OutboxStatus;
import com.cqrs.ordercommand.infrastructure.persistence.entity.*;
import com.cqrs.ordercommand.infrastructure.persistence.repository.OrderOutboxRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class OrderOutboxService {
    private final OrderOutboxRepository repository;
    private final ApiMapper mapper;
    private final com.cqrs.ordercommand.infrastructure.persistence.OutboxRelayStore relay;
    public OrderOutboxService(OrderOutboxRepository repository, ApiMapper mapper, com.cqrs.ordercommand.infrastructure.persistence.OutboxRelayStore relay) {
        this.repository = repository; this.mapper = mapper; this.relay=relay;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(java.util.Collection<OrderOutboxEntity> events) {
        repository.saveAllAndFlush(events);
    }
    @Transactional
    public void retry(UUID id) { relay.retry(id); }
    public OutboxResponse get(UUID id) {
        return mapper.outbox(repository.findById(id).orElseThrow(() -> new NotFoundException("Outbox")));
    }
    public PageResponse<OutboxResponse> list(UUID orderId, OutboxStatus status, com.cqrs.ordercommand.domain.model.ProjectionEventType eventType, String projectionKey, Pageable pageable) {
        Specification<OrderOutboxEntity> filter = (root, query, cb) -> cb.and(
                orderId == null ? cb.conjunction() : cb.equal(root.get("order").get("id"), orderId),
                status == null ? cb.conjunction() : cb.equal(root.get("status"), status),
                eventType == null ? cb.conjunction() : cb.equal(root.get("eventType"), eventType),
                projectionKey == null ? cb.conjunction() : cb.equal(root.get("projectionKey"), projectionKey));
        return PageResponse.from(repository.findAll(filter, pageable).map(mapper::outbox));
    }
}
