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
public class OrderSummaryQueryService {
    private final OrderSummaryRepository repository;
    private final ProjectionResponseMapper mapper;
    public OrderSummaryQueryService(OrderSummaryRepository repository,ProjectionResponseMapper mapper) { this.repository=repository;this.mapper=mapper; }
    public PageResponse<OrderSummaryResponse> list(UUID customerId,String status,Instant from,Instant to,int page,int size,String sort) {
        if(from!=null && to!=null && !from.isBefore(to)) throw new InvalidQueryException("from must be before to");
        Specification<OrderSummaryEntity> spec=(root,query,cb) -> {
            var predicates=new ArrayList<Predicate>();
            if(customerId!=null) predicates.add(cb.equal(root.get("customerId"),customerId));
            if(status!=null) predicates.add(cb.equal(root.get("status"),status));
            if(from!=null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),from));
            if(to!=null) predicates.add(cb.lessThan(root.get("createdAt"),to));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.from(repository.findAll(spec,QueryPages.of(page,size,sort,Set.of("createdAt","totalAmount"),"orderId")).map(mapper::orderSummary));
    }
}
