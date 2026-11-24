package com.cqrs.orderquery.application.query;

import com.cqrs.orderquery.application.dto.ProjectionResponses.CustomerDetailResponse;
import com.cqrs.orderquery.application.exception.QueryNotFoundException;
import com.cqrs.orderquery.application.mapper.ProjectionResponseMapper;
import com.cqrs.orderquery.infrastructure.persistence.repository.CustomerDetailRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class CustomerDetailQueryService {
    private final CustomerDetailRepository repository;
    private final ProjectionResponseMapper mapper;
    public CustomerDetailQueryService(CustomerDetailRepository repository,ProjectionResponseMapper mapper) { this.repository=repository;this.mapper=mapper; }
    public CustomerDetailResponse get(UUID id) { return mapper.customerDetail(repository.findById(id).orElseThrow(QueryNotFoundException::new)); }
}
