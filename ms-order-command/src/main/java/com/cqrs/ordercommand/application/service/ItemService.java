package com.cqrs.ordercommand.application.service;

import com.cqrs.ordercommand.application.dto.OrderDtos;
import com.cqrs.ordercommand.application.exception.*;
import com.cqrs.ordercommand.application.mapper.ApiMapper;
import com.cqrs.ordercommand.domain.model.Item;
import com.cqrs.ordercommand.infrastructure.persistence.entity.ProductEntity;
import com.cqrs.ordercommand.infrastructure.persistence.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ItemService {
    private final ProductRepository products;
    private final OrderRepository orders;
    private final ItemRepository items;
    private final ApiMapper mapper;
    public ItemService(ProductRepository products, OrderRepository orders, ItemRepository items, ApiMapper mapper) {
        this.products = products; this.orders = orders; this.items = items; this.mapper = mapper;
    }
    public List<OrderDtos.ItemResponse> list(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new NotFoundException("Order"))
                .getItems().stream().map(mapper::item).toList();
    }
    public OrderDtos.ItemResponse get(UUID orderId, UUID itemId) {
        return mapper.item(items.findByIdAndOrderId(itemId, orderId).orElseThrow(() -> new NotFoundException("Item")));
    }
    public PreparedItems prepare(List<OrderDtos.ItemRequest> input) {
        var ids = input.stream().map(OrderDtos.ItemRequest::productId).distinct().toList();
        var catalog = products.findAllById(ids).stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));
        for (var id : ids) {
            var product = catalog.get(id);
            if (product == null) throw new NotFoundException("Product " + id);
            if (!product.isActive()) throw new BusinessConflictException("Produto inativo nao pode ser incluido no pedido.");
        }
        var snapshots = input.stream().map(request -> {
            var product = catalog.get(request.productId());
            return new Item(UUID.randomUUID(), product.getId(), product.getSku(), product.getName(),
                    request.quantity(), product.getUnitPrice());
        }).toList();
        return new PreparedItems(snapshots, catalog);
    }
    public record PreparedItems(List<Item> items, Map<UUID, ProductEntity> catalog) {}
}
