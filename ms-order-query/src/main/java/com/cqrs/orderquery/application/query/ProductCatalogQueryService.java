package com.cqrs.orderquery.application.query;

import com.cqrs.orderquery.application.dto.*;
import com.cqrs.orderquery.application.dto.ProjectionResponses.*;
import com.cqrs.orderquery.application.mapper.ProjectionResponseMapper;
import com.cqrs.orderquery.application.exception.InvalidQueryException;
import com.cqrs.orderquery.infrastructure.persistence.entity.*;
import com.cqrs.orderquery.infrastructure.persistence.repository.*;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class ProductCatalogQueryService {
    private final ProductCatalogRepository repository;
    private final ProjectionResponseMapper mapper;
    public ProductCatalogQueryService(ProductCatalogRepository repository,ProjectionResponseMapper mapper) { this.repository=repository;this.mapper=mapper; }
    public PageResponse<ProductCatalogResponse> list(String search,String category,Boolean available,BigDecimal minPrice,BigDecimal maxPrice,int page,int size,String sort) {
        if(minPrice!=null && maxPrice!=null && minPrice.compareTo(maxPrice)>0) throw new InvalidQueryException("minPrice must be <= maxPrice");
        Specification<ProductCatalogEntity> spec=(root,query,cb) -> {
            var predicates=new ArrayList<Predicate>();
            if(search!=null && !search.isBlank()) predicates.add(cb.like(cb.lower(root.get("name")),"%"+search.strip().toLowerCase(Locale.ROOT)+"%"));
            if(category!=null) predicates.add(cb.equal(root.get("category"),category));
            if(available!=null) predicates.add(cb.equal(root.get("available"),available));
            if(minPrice!=null) predicates.add(cb.greaterThanOrEqualTo(root.get("price"),minPrice));
            if(maxPrice!=null) predicates.add(cb.lessThanOrEqualTo(root.get("price"),maxPrice));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.from(repository.findAll(spec,QueryPages.of(page,size,sort,Set.of("name","price"),"productId")).map(mapper::productCatalog));
    }
}
