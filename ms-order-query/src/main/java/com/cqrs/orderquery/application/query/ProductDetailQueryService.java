package com.cqrs.orderquery.application.query;

import com.cqrs.orderquery.application.dto.ProjectionResponses.ProductDetailResponse;
import com.cqrs.orderquery.application.exception.QueryNotFoundException;
import com.cqrs.orderquery.application.mapper.ProjectionResponseMapper;
import com.cqrs.orderquery.infrastructure.persistence.repository.ProductDetailRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class ProductDetailQueryService {
    private final ProductDetailRepository repository;
    private final ProjectionResponseMapper mapper;
    public ProductDetailQueryService(ProductDetailRepository repository,ProjectionResponseMapper mapper) { this.repository=repository;this.mapper=mapper; }
    public ProductDetailResponse get(UUID id) { return mapper.productDetail(repository.findById(id).orElseThrow(QueryNotFoundException::new)); }
}
