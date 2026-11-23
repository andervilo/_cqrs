package com.cqrs.ordercommand.application.mapper;

import org.mapstruct.*;
import com.cqrs.ordercommand.application.dto.*;
import com.cqrs.ordercommand.infrastructure.persistence.entity.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ApiMapper {
    CustomerDtos.Response customer(CustomerEntity entity);
    ProductDtos.Response product(ProductEntity entity);
    @Mapping(target = "customerId", source = "customer.id")
    OrderDtos.Response order(OrderEntity entity);
    @Mapping(target = "productId", source = "product.id")
    OrderDtos.ItemResponse item(ItemEntity entity);
    @Mapping(target = "orderId", source = "order.id")
    OutboxResponse outbox(OrderOutboxEntity entity);
}
