package com.cqrs.ordercommand.application.service;

import com.cqrs.ordercommand.application.dto.*;
import com.cqrs.ordercommand.application.exception.NotFoundException;
import com.cqrs.ordercommand.application.exception.BusinessConflictException;
import com.cqrs.ordercommand.application.mapper.ApiMapper;
import com.cqrs.ordercommand.infrastructure.persistence.entity.*;
import com.cqrs.ordercommand.infrastructure.persistence.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import com.cqrs.ordercommand.application.projection.ProjectionUpdateService;
import com.cqrs.ordercommand.infrastructure.persistence.ProjectionLocks;
import org.springframework.transaction.annotation.Isolation;

@Service
@Transactional(readOnly = true)
public class CustomerService {
    private final CustomerRepository repository;
    private final OrderRepository orders;
    private final ApiMapper mapper;
    private final ProjectionUpdateService projections;
    private final ProjectionLocks locks;
    public CustomerService(CustomerRepository repository, OrderRepository orders, ApiMapper mapper, ProjectionUpdateService projections, ProjectionLocks locks) {
        this.repository = repository; this.orders = orders; this.mapper = mapper; this.projections=projections; this.locks=locks;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CustomerDtos.Response create(CustomerDtos.Create input) {
        var entity = repository.saveAndFlush(new CustomerEntity(input.name(), input.email(), input.phone()));
        projections.customerCreated(entity.getId());
        return mapper.customer(entity);
    }
    public CustomerDtos.Response get(UUID id) { return mapper.customer(require(id)); }
    public PageResponse<CustomerDtos.Response> list(String q, Pageable pageable) {
        Specification<CustomerEntity> filter = (root, query, cb) -> {
            if (q == null || q.isBlank()) return cb.conjunction();
            String pattern = "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
            return cb.or(cb.like(cb.lower(root.get("name")), pattern), cb.like(root.get("email"), pattern));
        };
        return PageResponse.from(repository.findAll(filter, pageable).map(mapper::customer));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CustomerDtos.Response update(UUID id, CustomerDtos.Update input) {
        locks.customer(id);
        var entity = require(id);
        String oldName=entity.getName(), oldEmail=entity.getEmail(), oldPhone=entity.getPhone();
        entity.update(input.name(), input.email(), input.phone(), input.active());
        repository.flush();
        projections.customerUpdated(id, !Objects.equals(oldName,entity.getName()),
                !Objects.equals(oldEmail,entity.getEmail()), !Objects.equals(oldPhone,entity.getPhone()));
        return mapper.customer(entity);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(UUID id) {
        locks.customer(id);
        var entity = require(id);
        if (orders.existsByCustomerId(id)) throw new BusinessConflictException("Cliente possui pedidos e nao pode ser excluido.");
        projections.customerDeleted(id);
        repository.delete(entity);
        repository.flush();
    }
    private CustomerEntity require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Customer"));
    }
}
