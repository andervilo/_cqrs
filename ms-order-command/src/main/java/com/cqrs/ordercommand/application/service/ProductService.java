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

@Service
@Transactional(readOnly = true)
public class ProductService {
    private final ProductRepository repository;
    private final ItemRepository items;
    private final ApiMapper mapper;
    public ProductService(ProductRepository repository, ItemRepository items, ApiMapper mapper) {
        this.repository = repository; this.items = items; this.mapper = mapper;
    }
    @Transactional
    public ProductDtos.Response create(ProductDtos.Create input) {
        return mapper.product(repository.saveAndFlush(new ProductEntity(input.sku(), input.name(), input.description(), input.unitPrice())));
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
    @Transactional
    public ProductDtos.Response update(UUID id, ProductDtos.Update input) {
        var entity = require(id);
        entity.update(input.sku(), input.name(), input.description(), input.unitPrice(), input.active());
        return mapper.product(repository.saveAndFlush(entity));
    }
    @Transactional
    public void delete(UUID id) {
        var entity = require(id);
        if (items.existsByProductId(id)) throw new BusinessConflictException("Produto pertence a pedidos e nao pode ser excluido.");
        repository.delete(entity);
        repository.flush();
    }
    private ProductEntity require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Product"));
    }
}
