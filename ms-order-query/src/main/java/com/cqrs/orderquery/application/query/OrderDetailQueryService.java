package com.cqrs.orderquery.application.query;

import com.cqrs.orderquery.application.dto.ProjectionResponses.OrderDetailResponse;
import com.cqrs.orderquery.application.exception.QueryNotFoundException;
import com.cqrs.orderquery.application.mapper.ProjectionResponseMapper;
import com.cqrs.orderquery.infrastructure.persistence.repository.OrderDetailRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class OrderDetailQueryService {
    private final OrderDetailRepository repository;
    private final ProjectionResponseMapper mapper;
    public OrderDetailQueryService(OrderDetailRepository repository,ProjectionResponseMapper mapper) { this.repository=repository;this.mapper=mapper; }
    public OrderDetailResponse get(UUID id) { return mapper.orderDetail(repository.findById(id).orElseThrow(QueryNotFoundException::new)); }
}
