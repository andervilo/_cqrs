package com.cqrs.orderquery.infrastructure.config;

import com.cqrs.orderquery.application.inbox.*;
import com.cqrs.orderquery.application.inbox.ProjectionPayloads.*;
import com.cqrs.orderquery.domain.ProjectionEventType;
import com.cqrs.orderquery.infrastructure.persistence.JdbcProjectionHandler;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false)
public class ProjectionHandlerConfiguration {
    @Bean
    ProjectionHandler customerSummaryHandler(JdbcTemplate jdbc,ObjectMapper json) {
        return new JdbcProjectionHandler<>(ProjectionEventType.CUSTOMER_SUMMARY,"customer_summary_projection","customer_id",CustomerSummary.class,p -> {
            var fields=new LinkedHashMap<String,Object>();
            fields.put("name",p.name());
            fields.put("email",p.email());
            fields.put("order_count",p.orderCount());
            fields.put("last_order_at",p.lastOrderAt());
            return fields;
        },Set.of(),jdbc,json);
    }
    @Bean
    ProjectionHandler customerDetailHandler(JdbcTemplate jdbc,ObjectMapper json) {
        return new JdbcProjectionHandler<>(ProjectionEventType.CUSTOMER_DETAIL,"customer_detail_projection","customer_id",CustomerDetail.class,p -> {
            var fields=new LinkedHashMap<String,Object>();
            fields.put("name",p.name());
            fields.put("email",p.email());
            fields.put("phone",p.phone());
            fields.put("addresses",p.addresses());
            fields.put("created_at",p.createdAt());
            fields.put("order_count",p.orderCount());
            fields.put("lifetime_value",p.lifetimeValue());
            return fields;
        },Set.of("addresses"),jdbc,json);
    }
    @Bean
    ProjectionHandler productCatalogHandler(JdbcTemplate jdbc,ObjectMapper json) {
        return new JdbcProjectionHandler<>(ProjectionEventType.PRODUCT_CATALOG,"product_catalog_projection","product_id",ProductCatalog.class,p -> {
            var fields=new LinkedHashMap<String,Object>();
            fields.put("name",p.name());
            fields.put("price",p.price());
            fields.put("currency",p.currency());
            fields.put("thumbnail_url",p.thumbnailUrl());
            fields.put("available",p.available());
            fields.put("category",p.category());
            return fields;
        },Set.of(),jdbc,json);
    }
    @Bean
    ProjectionHandler productDetailHandler(JdbcTemplate jdbc,ObjectMapper json) {
        return new JdbcProjectionHandler<>(ProjectionEventType.PRODUCT_DETAIL,"product_detail_projection","product_id",ProductDetail.class,p -> {
            var fields=new LinkedHashMap<String,Object>();
            fields.put("name",p.name());
            fields.put("description",p.description());
            fields.put("price",p.price());
            fields.put("currency",p.currency());
            fields.put("image_urls",p.imageUrls());
            fields.put("available",p.available());
            fields.put("attributes",p.attributes());
            return fields;
        },Set.of("image_urls","attributes"),jdbc,json);
    }
    @Bean
    ProjectionHandler orderSummaryHandler(JdbcTemplate jdbc,ObjectMapper json) {
        return new JdbcProjectionHandler<>(ProjectionEventType.ORDER_SUMMARY,"order_summary_projection","order_id",OrderSummary.class,p -> {
            var fields=new LinkedHashMap<String,Object>();
            fields.put("customer_id",p.customerId());
            fields.put("customer_name",p.customerName());
            fields.put("created_at",p.createdAt());
            fields.put("total_amount",p.totalAmount());
            fields.put("currency",p.currency());
            fields.put("status",p.status());
            fields.put("item_count",p.itemCount());
            return fields;
        },Set.of(),jdbc,json);
    }
    @Bean
    ProjectionHandler orderDetailHandler(JdbcTemplate jdbc,ObjectMapper json) {
        return new JdbcProjectionHandler<>(ProjectionEventType.ORDER_DETAIL,"order_detail_projection","order_id",OrderDetail.class,p -> {
            var fields=new LinkedHashMap<String,Object>();
            fields.put("created_at",p.createdAt());
            fields.put("customer_id",p.customer().customerId());
            fields.put("customer_name",p.customer().name());
            fields.put("items",p.items());
            fields.put("subtotal_amount",p.subtotalAmount());
            fields.put("discount_amount",p.discountAmount());
            fields.put("shipping_amount",p.shippingAmount());
            fields.put("total_amount",p.totalAmount());
            fields.put("currency",p.currency());
            fields.put("status",p.status());
            return fields;
        },Set.of("items"),jdbc,json);
    }
    @Bean
    ProjectionHandler customerOrderHistoryHandler(JdbcTemplate jdbc,ObjectMapper json) {
        return new JdbcProjectionHandler<>(ProjectionEventType.CUSTOMER_ORDER_HISTORY,"customer_order_history_projection","order_id",CustomerOrderHistory.class,p -> {
            var fields=new LinkedHashMap<String,Object>();
            fields.put("customer_id",p.customerId());
            fields.put("created_at",p.createdAt());
            fields.put("status",p.status());
            fields.put("total_amount",p.totalAmount());
            fields.put("currency",p.currency());
            fields.put("item_count",p.itemCount());
            fields.put("first_product_name",p.firstProductName());
            return fields;
        },Set.of(),jdbc,json);
    }
}
