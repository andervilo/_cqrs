package com.cqrs.ordercommand;

import com.cqrs.ordercommand.application.dto.*;
import com.cqrs.ordercommand.application.projection.*;
import com.cqrs.ordercommand.application.service.*;
import com.cqrs.ordercommand.domain.model.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "outbox.relay.enabled=false")
class ProjectionOutboxTests {
    @Autowired CustomerService customers;
    @Autowired ProductService products;
    @Autowired OrderService orders;
    @Autowired ProjectionRebuildService rebuild;
    @Autowired ProjectionRebuildWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    CustomerDtos.Response customer() {
        return customers.create(new CustomerDtos.Create("Maria",UUID.randomUUID()+"@example.com",null));
    }
    ProductDtos.Response product() {
        return products.create(new ProductDtos.Create(UUID.randomUUID().toString(),"Teclado","USB",new BigDecimal("12.30")));
    }
    OrderDtos.Response order(UUID customer, UUID product) {
        return orders.create(new OrderDtos.Create(customer,List.of(new OrderDtos.ItemRequest(product,3)),null));
    }
    long count(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox WHERE projection_key=?",Long.class,id.toString());
    }
    JsonNode latest(ProjectionEventType type, UUID id) {
        var payload = jdbc.queryForObject("SELECT payload::text FROM order_command.order_outbox WHERE event_type=? AND projection_key=? ORDER BY projection_version DESC LIMIT 1",
                String.class,type.name(),id.toString());
        return json.readTree(payload);
    }
    long version(ProjectionEventType type, UUID id) {
        return jdbc.queryForObject("SELECT version FROM order_command.projection_revision WHERE event_type=? AND projection_key=?",Long.class,type.name(),id.toString());
    }

    @Test
    void emitsSevenSpecificContractsAndFiveRowsPerOrderCreation() {
        var c=customer(); var p=product();
        assertThat(count(c.id())).isEqualTo(2);
        assertThat(count(p.id())).isEqualTo(2);
        var summary=latest(ProjectionEventType.CUSTOMER_SUMMARY,c.id());
        assertThat(summary.path("orderCount").asLong()).isZero();
        assertThat(summary.path("lastOrderAt").isNull()).isTrue();
        var o=order(c.id(),p.id());
        assertThat(count(o.id())).isEqualTo(3);
        assertThat(count(c.id())).isEqualTo(4);
        assertThat(latest(ProjectionEventType.CUSTOMER_SUMMARY,c.id()).path("orderCount").asLong()).isEqualTo(1);
        var detail=latest(ProjectionEventType.ORDER_DETAIL,o.id());
        assertThat(detail.has("data")).isFalse();
        assertThat(detail.has("eventType")).isFalse();
        assertThat(detail.path("items").size()).isEqualTo(1);
        assertThat(detail.path("totalAmount").decimalValue()).isEqualByComparingTo("36.90");
        assertThat(detail.path("customer").path("name").asText()).isEqualTo("Maria");
        assertThat(latest(ProjectionEventType.ORDER_SUMMARY,o.id()).path("itemCount").asInt()).isEqualTo(3);
        assertThat(latest(ProjectionEventType.CUSTOMER_ORDER_HISTORY,o.id()).path("customerId").asText()).isEqualTo(c.id().toString());
        assertThat(latest(ProjectionEventType.PRODUCT_CATALOG,p.id()).path("price").decimalValue()).isEqualByComparingTo("12.30");
        assertThat(latest(ProjectionEventType.PRODUCT_DETAIL,p.id()).path("imageUrls").isArray()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox WHERE projection_key IN (?,?) AND order_id IS NOT NULL",
                Integer.class,c.id().toString(),p.id().toString())).isZero();
        orders.replaceItems(o.id(),new OrderDtos.ReplaceItems(List.of(new OrderDtos.ItemRequest(p.id(),2))));
        orders.cancel(o.id());
        assertThat(version(ProjectionEventType.ORDER_DETAIL,o.id())).isEqualTo(3);
        assertThat(latest(ProjectionEventType.ORDER_DETAIL,o.id()).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(latest(ProjectionEventType.CUSTOMER_DETAIL,c.id()).path("lifetimeValue").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    void onlyEmitsForChangedFieldsAndPropagatesCustomerNameWithoutRepricingHistory() {
        var c=customer(); var p=product(); var o=order(c.id(),p.id());
        products.update(p.id(),new ProductDtos.Update(p.sku(),p.name(),"Descricao nova",p.unitPrice(),true));
        assertThat(count(p.id())).isEqualTo(3);
        assertThat(version(ProjectionEventType.PRODUCT_CATALOG,p.id())).isEqualTo(1);
        assertThat(count(o.id())).isEqualTo(3);
        products.update(p.id(),new ProductDtos.Update("SKU-"+UUID.randomUUID(),p.name(),"Descricao nova",p.unitPrice(),true));
        assertThat(count(p.id())).isEqualTo(3);
        products.update(p.id(),new ProductDtos.Update(p.sku(),"Nome novo",null,new BigDecimal("99.99"),true));
        assertThat(latest(ProjectionEventType.ORDER_DETAIL,o.id()).path("items").get(0).path("unitPrice").decimalValue()).isEqualByComparingTo("12.30");
        assertThat(count(o.id())).isEqualTo(3);
        customers.update(c.id(),new CustomerDtos.Update(c.name(),c.email(),null,false));
        assertThat(count(c.id())).isEqualTo(4);
        customers.update(c.id(),new CustomerDtos.Update("Maria atualizada",c.email(),"123456789",false));
        assertThat(count(o.id())).isEqualTo(5);
        assertThat(count(c.id())).isEqualTo(6);
        assertThat(latest(ProjectionEventType.ORDER_SUMMARY,o.id()).path("customerName").asText()).isEqualTo("Maria atualizada");
        assertThat(version(ProjectionEventType.CUSTOMER_ORDER_HISTORY,o.id())).isEqualTo(1);
    }

    @Test
    void createsTombstonesAndRebuildKeepsRevisionsForDeletedAndExistingResources() {
        var c=customer(); var p=product(); var o=order(c.id(),p.id());
        long before=version(ProjectionEventType.ORDER_DETAIL,o.id());
        worker.customer(c.id()); worker.product(p.id());
        assertThat(version(ProjectionEventType.ORDER_DETAIL,o.id())).isEqualTo(before+1);
        assertThat(version(ProjectionEventType.CUSTOMER_SUMMARY,c.id())).isEqualTo(3);
        var disposable=product(); products.delete(disposable.id());
        assertThat(latest(ProjectionEventType.PRODUCT_DETAIL,disposable.id()).isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox WHERE projection_key=? AND operation='DELETE'",
                Integer.class,disposable.id().toString())).isEqualTo(2);
        worker.product(disposable.id());
        assertThat(version(ProjectionEventType.PRODUCT_DETAIL,disposable.id())).isEqualTo(3);
        var empty=customer(); customers.delete(empty.id()); worker.customer(empty.id());
        assertThat(version(ProjectionEventType.CUSTOMER_DETAIL,empty.id())).isEqualTo(3);
        var page=rebuild.rebuild(ProjectionRebuildService.ResourceType.CUSTOMER,null,1);
        assertThat(page.processed()).isEqualTo(1);
        assertThat(page.nextCursor()).isNotNull();
        var next=rebuild.rebuild(ProjectionRebuildService.ResourceType.CUSTOMER,page.nextCursor(),1);
        if(next.processed()>0) assertThat(next.nextCursor().toString()).isGreaterThan(page.nextCursor().toString());
    }

    @Test
    void concurrentOrdersAndRenameProduceMonotonicCompleteSnapshots() throws Exception {
        var c=customer(); var p=product();
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++) futures.add(executor.submit(() -> {
                await(start); order(c.id(),p.id());
            }));
            futures.add(executor.submit(() -> {
                await(start); customers.update(c.id(),new CustomerDtos.Update("Nome final",c.email(),null,true));
            }));
            futures.add(executor.submit(() -> { await(start); worker.customer(c.id()); }));
            start.countDown();
            for(var future:futures) future.get(45,TimeUnit.SECONDS);
        }
        var latest=latest(ProjectionEventType.CUSTOMER_SUMMARY,c.id());
        assertThat(latest.path("orderCount").asLong()).isEqualTo(4);
        assertThat(latest.path("name").asText()).isEqualTo("Nome final");
        assertThat(version(ProjectionEventType.CUSTOMER_SUMMARY,c.id())).isEqualTo(7);
        var ids=jdbc.queryForList("SELECT id FROM order_command.orders WHERE customer_id=?",UUID.class,c.id());
        for(var id:ids) assertThat(latest(ProjectionEventType.ORDER_SUMMARY,id).path("customerName").asText()).isEqualTo("Nome final");
        var counts=jdbc.queryForList("SELECT (payload->>'orderCount')::int FROM order_command.order_outbox WHERE projection_key=? AND event_type='CUSTOMER_SUMMARY' ORDER BY projection_version",Integer.class,c.id().toString());
        assertThat(counts).isSorted();
    }
    private void await(CountDownLatch start) {
        try { if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("start timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
