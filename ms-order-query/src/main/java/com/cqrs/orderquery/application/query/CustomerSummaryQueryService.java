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
public class CustomerSummaryQueryService {
    private final CustomerSummaryRepository repository;
    private final ProjectionResponseMapper mapper;
    public CustomerSummaryQueryService(CustomerSummaryRepository repository,ProjectionResponseMapper mapper) { this.repository=repository;this.mapper=mapper; }
    public PageResponse<CustomerSummaryResponse> list(String search,int page,int size,String sort) {
        Specification<CustomerSummaryEntity> spec=(root,query,cb) -> {
            var predicates=new ArrayList<Predicate>();
            if(search!=null && !search.isBlank()) {
                String pattern="%"+search.strip().toLowerCase(Locale.ROOT)+"%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("name")),pattern),cb.like(cb.lower(root.get("email")),pattern)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.from(repository.findAll(spec,QueryPages.of(page,size,sort,Set.of("name","lastOrderAt"),"customerId")).map(mapper::customerSummary));
    }
}
