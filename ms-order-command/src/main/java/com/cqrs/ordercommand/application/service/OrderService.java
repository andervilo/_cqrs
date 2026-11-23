package com.cqrs.ordercommand.application.service;

import com.cqrs.ordercommand.application.dto.*;
import com.cqrs.ordercommand.application.exception.NotFoundException;
import com.cqrs.ordercommand.application.exception.BusinessConflictException;
import com.cqrs.ordercommand.application.mapper.ApiMapper;
import com.cqrs.ordercommand.domain.model.*;
import com.cqrs.ordercommand.infrastructure.persistence.entity.*;
import com.cqrs.ordercommand.infrastructure.persistence.repository.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import com.cqrs.ordercommand.application.projection.ProjectionUpdateService;
import com.cqrs.ordercommand.infrastructure.persistence.ProjectionLocks;
import org.springframework.transaction.annotation.Isolation;

@Service
@Transactional(readOnly = true)
public class OrderService {
    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final ItemService items;
    private final ProjectionUpdateService projections;
    private final ProjectionLocks locks;
    private final ApiMapper mapper;
    public OrderService(OrderRepository orders, CustomerRepository customers, ItemService items,
                        ProjectionUpdateService projections, ProjectionLocks locks, ApiMapper mapper) {
        this.orders = orders; this.customers = customers; this.items = items;
        this.projections=projections; this.locks=locks; this.mapper = mapper;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDtos.Response create(OrderDtos.Create input) {
        locks.customer(input.customerId());
        var customer = customers.findById(input.customerId()).orElseThrow(() -> new NotFoundException("Customer"));
        if (!customer.isActive()) throw new BusinessConflictException("Cliente inativo nao pode criar pedidos.");
        var prepared = items.prepare(input.items());
        var order = Order.create(customer.getId(), prepared.items(), input.notes());
        var entity = orders.saveAndFlush(new OrderEntity(order, customer, prepared.catalog()));
        projections.orderChanged(entity.getId(), entity.getCustomer().getId(), true);
        return mapper.order(entity);
    }
    public OrderDtos.Response get(UUID id) { return mapper.order(require(id)); }
    public PageResponse<OrderDtos.Response> list(UUID customerId, OrderStatus status, Pageable pageable) {
        Specification<OrderEntity> filter = (root, query, cb) -> cb.and(
                customerId == null ? cb.conjunction() : cb.equal(root.get("customer").get("id"), customerId),
                status == null ? cb.conjunction() : cb.equal(root.get("status"), status));
        return PageResponse.from(orders.findAll(filter, pageable).map(mapper::order));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDtos.Response replaceItems(UUID id, OrderDtos.ReplaceItems input) {
        locks.orderCustomer(id);
        var entity = require(id);
        var prepared = items.prepare(input.items());
        var changed = entity.toDomain().replaceItems(prepared.items());
        entity.replaceItems(changed, prepared.catalog());
        orders.flush();
        projections.orderChanged(entity.getId(), entity.getCustomer().getId(), false);
        return mapper.order(entity);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDtos.Response cancel(UUID id) {
        locks.orderCustomer(id);
        var entity = require(id);
        entity.cancel(entity.toDomain().cancel());
        orders.flush();
        projections.orderChanged(entity.getId(), entity.getCustomer().getId(), false);
        return mapper.order(entity);
    }
    private OrderEntity require(UUID id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("Order"));
    }
}
