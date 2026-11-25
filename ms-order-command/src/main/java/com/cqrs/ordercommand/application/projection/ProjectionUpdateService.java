package com.cqrs.ordercommand.application.projection;

import com.cqrs.ordercommand.application.service.OrderOutboxService;
import com.cqrs.ordercommand.domain.model.*;
import com.cqrs.ordercommand.infrastructure.persistence.ProjectionRevisionStore;
import com.cqrs.ordercommand.infrastructure.persistence.entity.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.cqrs.ordercommand.domain.model.ProjectionEventType.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ProjectionUpdateService {
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final ProjectionRevisionStore revisions;
    private final CustomerProjectionBuilder customers;
    private final ProductProjectionBuilder products;
    private final OrderProjectionBuilder orders;
    private final ProjectionPayloadSerializer serializer;
    private final OrderOutboxService outbox;
    public ProjectionUpdateService(JdbcTemplate jdbc, EntityManager em, ProjectionRevisionStore revisions,
            CustomerProjectionBuilder customers, ProductProjectionBuilder products, OrderProjectionBuilder orders,
            ProjectionPayloadSerializer serializer, OrderOutboxService outbox) {
        this.jdbc=jdbc; this.em=em; this.revisions=revisions; this.customers=customers;
        this.products=products; this.orders=orders; this.serializer=serializer; this.outbox=outbox;
    }
    public void customerCreated(UUID id) { emit(customerTargets(id), false); }
    public void customerUpdated(UUID id, boolean nameChanged, boolean emailChanged, boolean phoneChanged) {
        var targets = new TreeSet<ProjectionTarget>();
        if (nameChanged || emailChanged) targets.addAll(customerTargets(id));
        if (phoneChanged) targets.add(new ProjectionTarget(CUSTOMER_DETAIL,id));
        if (nameChanged) {
            orderIds(id).forEach(order -> {
                targets.add(new ProjectionTarget(ORDER_SUMMARY,order));
                targets.add(new ProjectionTarget(ORDER_DETAIL,order));
            });
        }
        emit(targets, false);
    }
    public void customerDeleted(UUID id) { emit(customerTargets(id), true); }
    public void productCreated(UUID id) { emit(productTargets(id), false); }
    public void productUpdated(UUID id, boolean catalogChanged, boolean descriptionChanged) {
        var targets = new TreeSet<ProjectionTarget>();
        if (catalogChanged) targets.addAll(productTargets(id));
        if (descriptionChanged) targets.add(new ProjectionTarget(PRODUCT_DETAIL,id));
        emit(targets, false);
    }
    public void productDeleted(UUID id) { emit(productTargets(id), true); }
    public void orderChanged(UUID orderId, UUID customerId, boolean created) {
        var targets = new TreeSet<>(orderTargets(orderId));
        targets.add(new ProjectionTarget(CUSTOMER_DETAIL,customerId));
        if (created) targets.add(new ProjectionTarget(CUSTOMER_SUMMARY,customerId));
        emit(targets,false);
    }
    public void rebuildCustomer(UUID id) {
        var targets = new TreeSet<>(customerTargets(id));
        orderIds(id).forEach(order -> targets.addAll(orderTargets(order)));
        emit(targets,false);
    }
    public void rebuildProduct(UUID id) { emit(productTargets(id),false); }
    private List<UUID> orderIds(UUID id) {
        return jdbc.queryForList("SELECT id FROM order_command.orders WHERE customer_id=? ORDER BY id", UUID.class,id);
    }
    private Set<ProjectionTarget> customerTargets(UUID id) {
        return Set.of(new ProjectionTarget(CUSTOMER_SUMMARY,id), new ProjectionTarget(CUSTOMER_DETAIL,id));
    }
    private Set<ProjectionTarget> productTargets(UUID id) {
        return Set.of(new ProjectionTarget(PRODUCT_CATALOG,id), new ProjectionTarget(PRODUCT_DETAIL,id));
    }
    private Set<ProjectionTarget> orderTargets(UUID id) {
        return Set.of(new ProjectionTarget(ORDER_SUMMARY,id),new ProjectionTarget(ORDER_DETAIL,id),
                new ProjectionTarget(CUSTOMER_ORDER_HISTORY,id));
    }
    private void emit(Collection<ProjectionTarget> targets, boolean deleted) {
        if (targets.isEmpty()) return;
        em.flush();
        // Advance/lock every key before reading snapshots. All locks live until the outer commit.
        var versions = revisions.advance(targets);
        var customerCache = new HashMap<UUID,CustomerProjectionBuilder.Views>();
        var productCache = new HashMap<UUID,ProductProjectionBuilder.Views>();
        var orderCache = new HashMap<UUID,OrderProjectionBuilder.Views>();
        var events = new ArrayList<OrderOutboxEntity>();
        versions.forEach((target,version) -> {
            Object snapshot = deleted ? Map.of() : switch (target.type()) {
                case CUSTOMER_SUMMARY -> customerCache.computeIfAbsent(target.id(),customers::build).summary();
                case CUSTOMER_DETAIL -> customerCache.computeIfAbsent(target.id(),customers::build).detail();
                case PRODUCT_CATALOG -> productCache.computeIfAbsent(target.id(),products::build).catalog();
                case PRODUCT_DETAIL -> productCache.computeIfAbsent(target.id(),products::build).detail();
                case ORDER_SUMMARY -> orderCache.computeIfAbsent(target.id(),orders::build).summary();
                case ORDER_DETAIL -> orderCache.computeIfAbsent(target.id(),orders::build).detail();
                case CUSTOMER_ORDER_HISTORY -> orderCache.computeIfAbsent(target.id(),orders::build).history();
                case SALES_DASHBOARD -> throw new UnsupportedOperationException(
                        "SALES_DASHBOARD is emitted by DashboardRefreshWorker via dashboard_refresh_request, not by the generic projection path");
            };
            var order = switch (target.type()) {
                case ORDER_SUMMARY, ORDER_DETAIL, CUSTOMER_ORDER_HISTORY -> em.getReference(OrderEntity.class,target.id());
                default -> null;
            };
            events.add(new OrderOutboxEntity(order,target.type(),
                    deleted ? ProjectionOperation.DELETE : ProjectionOperation.UPSERT,
                    target.key(),version,serializer.serialize(snapshot)));
        });
        outbox.append(events);
    }
}
