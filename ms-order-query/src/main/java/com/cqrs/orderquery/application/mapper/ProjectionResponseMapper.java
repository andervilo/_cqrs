package com.cqrs.orderquery.application.mapper;

import com.cqrs.orderquery.application.dto.ProjectionResponses.*;
import com.cqrs.orderquery.infrastructure.persistence.entity.*;
import java.util.*;
import org.mapstruct.*;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.*;

@Mapper(componentModel="spring",unmappedTargetPolicy=ReportingPolicy.ERROR,imports=CustomerInfoResponse.class)
public abstract class ProjectionResponseMapper {
    @Autowired protected ObjectMapper json;
    public abstract CustomerSummaryResponse customerSummary(CustomerSummaryEntity entity);
    @Mapping(target="addresses",qualifiedByName="addresses")
    public abstract CustomerDetailResponse customerDetail(CustomerDetailEntity entity);
    public abstract ProductCatalogResponse productCatalog(ProductCatalogEntity entity);
    @Mapping(target="imageUrls",qualifiedByName="images")
    @Mapping(target="attributes",qualifiedByName="attributes")
    public abstract ProductDetailResponse productDetail(ProductDetailEntity entity);
    public abstract OrderSummaryResponse orderSummary(OrderSummaryEntity entity);
    @Mapping(target="items",qualifiedByName="items")
    @Mapping(target="customer",expression="java(new CustomerInfoResponse(entity.getCustomerId(),entity.getCustomerName()))")
    public abstract OrderDetailResponse orderDetail(OrderDetailEntity entity);
    public abstract CustomerOrderHistoryResponse customerOrderHistory(CustomerOrderHistoryEntity entity);
    @Named("addresses") protected List<JsonNode> addresses(String value) { return json.readValue(value,new TypeReference<List<JsonNode>>(){}); }
    @Named("images") protected List<String> images(String value) { return json.readValue(value,new TypeReference<List<String>>(){}); }
    @Named("attributes") protected Map<String,String> attributes(String value) { return json.readValue(value,new TypeReference<Map<String,String>>(){}); }
    @Named("items") protected List<OrderItemResponse> items(String value) { return json.readValue(value,new TypeReference<List<OrderItemResponse>>(){}); }
}
