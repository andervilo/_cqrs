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
public class CustomerOrderHistoryQueryService {
    private final CustomerOrderHistoryRepository repository;
    private final ProjectionResponseMapper mapper;
    public CustomerOrderHistoryQueryService(CustomerOrderHistoryRepository repository,ProjectionResponseMapper mapper) { this.repository=repository;this.mapper=mapper; }
    public PageResponse<CustomerOrderHistoryResponse> list(UUID customerId,int page,int size,String sort) {
        Specification<CustomerOrderHistoryEntity> spec=(root,query,cb) -> {
            var predicates=new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("customerId"),customerId));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.from(repository.findAll(spec,QueryPages.of(page,size,sort,Set.of("createdAt","totalAmount"),"orderId")).map(mapper::customerOrderHistory));
    }
}
