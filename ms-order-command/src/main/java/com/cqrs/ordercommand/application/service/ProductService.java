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
public class ProductService {
    private final ProductRepository repository;
    private final ItemRepository items;
    private final ApiMapper mapper;
    private final ProjectionUpdateService projections;
    private final ProjectionLocks locks;
    public ProductService(ProductRepository repository, ItemRepository items, ApiMapper mapper, ProjectionUpdateService projections, ProjectionLocks locks) {
        this.repository = repository; this.items = items; this.mapper = mapper; this.projections=projections; this.locks=locks;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProductDtos.Response create(ProductDtos.Create input) {
        var entity=repository.saveAndFlush(new ProductEntity(input.sku(), input.name(), input.description(), input.unitPrice()));
        projections.productCreated(entity.getId());
        return mapper.product(entity);
    }
    public ProductDtos.Response get(UUID id) { return mapper.product(require(id)); }
    public PageResponse<ProductDtos.Response> list(String q, Pageable pageable) {
        Specification<ProductEntity> filter = (root, query, cb) -> {
            if (q == null || q.isBlank()) return cb.conjunction();
            String pattern = "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
            return cb.or(cb.like(cb.lower(root.get("name")), pattern), cb.like(cb.lower(root.get("sku")), pattern));
        };
        return PageResponse.from(repository.findAll(filter, pageable).map(mapper::product));
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProductDtos.Response update(UUID id, ProductDtos.Update input) {
        locks.product(id);
        var entity = require(id);
        String oldName=entity.getName(), oldDescription=entity.getDescription();
        var oldPrice=entity.getUnitPrice();
        boolean oldActive=entity.isActive();
        entity.update(input.sku(), input.name(), input.description(), input.unitPrice(), input.active());
        repository.flush();
        projections.productUpdated(id, !Objects.equals(oldName,entity.getName())
                || oldPrice.compareTo(entity.getUnitPrice())!=0 || oldActive!=entity.isActive(),
                !Objects.equals(oldDescription,entity.getDescription()));
        return mapper.product(entity);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(UUID id) {
        locks.product(id);
        var entity = require(id);
        if (items.existsByProductId(id)) throw new BusinessConflictException("Produto pertence a pedidos e nao pode ser excluido.");
        projections.productDeleted(id);
        repository.delete(entity);
        repository.flush();
    }
    private ProductEntity require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Product"));
    }
}
