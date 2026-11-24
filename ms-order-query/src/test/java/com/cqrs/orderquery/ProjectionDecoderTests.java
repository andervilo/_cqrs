package com.cqrs.orderquery;

import com.cqrs.orderquery.application.inbox.*;
import com.cqrs.orderquery.domain.*;
import com.cqrs.orderquery.infrastructure.messaging.ProjectionEventDecoder;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectionDecoderTests {
    private static ValidatorFactory validation;
    private ProjectionEventDecoder decoder;
    @BeforeAll static void validation() { validation=Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void closeValidation() { validation.close(); }
    @BeforeEach void prepare() {
        var classes=Map.of(ProjectionEventType.CUSTOMER_SUMMARY,ProjectionPayloads.CustomerSummary.class,
            ProjectionEventType.CUSTOMER_DETAIL,ProjectionPayloads.CustomerDetail.class,
            ProjectionEventType.PRODUCT_CATALOG,ProjectionPayloads.ProductCatalog.class,
            ProjectionEventType.PRODUCT_DETAIL,ProjectionPayloads.ProductDetail.class,
            ProjectionEventType.ORDER_SUMMARY,ProjectionPayloads.OrderSummary.class,
            ProjectionEventType.ORDER_DETAIL,ProjectionPayloads.OrderDetail.class,
            ProjectionEventType.CUSTOMER_ORDER_HISTORY,ProjectionPayloads.CustomerOrderHistory.class);
        var handlers=new ArrayList<ProjectionHandler>();
        classes.forEach((type,clazz) -> {
            var handler=mock(ProjectionHandler.class);
            when(handler.type()).thenReturn(type);
            doReturn(clazz).when(handler).payloadType();
            handlers.add(handler);
        });
        decoder=new ProjectionEventDecoder(JsonMapper.builder().build(),validation.getValidator(),new ProjectionHandlerRegistry(handlers));
    }
    @Test void acceptsEveryPublishedContractWithoutCommandDependency() {
        for(var type:ProjectionEventType.values()) {
            if(type==ProjectionEventType.SALES_DASHBOARD) continue;
            UUID key=UUID.randomUUID();var decoded=decoder.decode(ProjectionFixtures.event(type,key,1));
            assertThat(decoded.snapshot().identity()).isEqualTo(key);
            assertThat(decoded.contentHash()).hasSize(32);
        }
    }
    @Test void normalizesJsonOrderWhitespaceMoneyAndOffsetsForDuplicateFingerprint() {
        UUID key=UUID.randomUUID(),id=UUID.randomUUID();
        String original=ProjectionFixtures.payload(ProjectionEventType.PRODUCT_CATALOG,key);
        var first=ProjectionFixtures.event(ProjectionEventType.PRODUCT_CATALOG,key,1,id,ProjectionOperation.UPSERT,original);
        var reordered=JsonMapper.builder().build().readValue(original,new tools.jackson.core.type.TypeReference<TreeMap<String,Object>>(){});
        String equivalent=JsonMapper.builder().build().writeValueAsString(reordered).replace("150.0","150.00");
        var second=ProjectionFixtures.event(ProjectionEventType.PRODUCT_CATALOG,key,1,id,ProjectionOperation.UPSERT,equivalent);
        second.headers().remove("occurredAt");ProjectionFixtures.add(second,"occurredAt","2026-11-23T12:00:00-03:00");
        assertThat(decoder.decode(first).contentHash()).isEqualTo(decoder.decode(second).contentHash());
    }
    @Test void rejectsMissingRepeatedOrInvalidMetadataAndUnsupportedContracts() {
        UUID key=UUID.randomUUID();var missing=ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,1);
        missing.headers().remove("eventId");
        assertThatThrownBy(() -> decoder.decode(missing)).isInstanceOf(InvalidProjectionEvent.class);
        var repeated=ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,1);
        ProjectionFixtures.add(repeated,"eventType","CUSTOMER_SUMMARY");
        assertThatThrownBy(() -> decoder.decode(repeated)).isInstanceOf(InvalidProjectionEvent.class);
        var invalid=ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,0);
        assertThatThrownBy(() -> decoder.decode(invalid)).isInstanceOf(InvalidProjectionEvent.class);
        var unknown=ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,1);
        unknown.headers().remove("eventType");ProjectionFixtures.add(unknown,"eventType","SALES_DASHBOARD");
        assertThatThrownBy(() -> decoder.decode(unknown)).isInstanceOf(InvalidProjectionEvent.class);
        var schema=ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,1);
        schema.headers().remove("schemaVersion");ProjectionFixtures.add(schema,"schemaVersion","2");
        assertThatThrownBy(() -> decoder.decode(schema)).isInstanceOf(InvalidProjectionEvent.class);
    }
    @Test void rejectsConflictingPayloadIdsMalformedFieldsAndInconsistentMoney() {
        UUID key=UUID.randomUUID();
        for(String payload:List.of("{", "null", "{}",ProjectionFixtures.payload(ProjectionEventType.CUSTOMER_SUMMARY,UUID.randomUUID()))) {
            var event=ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,1,UUID.randomUUID(),ProjectionOperation.UPSERT,payload);
            assertThatThrownBy(() -> decoder.decode(event)).isInstanceOf(InvalidProjectionEvent.class);
        }
        var order=ProjectionFixtures.payload(ProjectionEventType.ORDER_DETAIL,key).replace("\"subtotal\": 300.00","\"subtotal\": 299.00");
        assertThatThrownBy(() -> decoder.decode(ProjectionFixtures.event(ProjectionEventType.ORDER_DETAIL,key,1,UUID.randomUUID(),ProjectionOperation.UPSERT,order))).isInstanceOf(InvalidProjectionEvent.class);
        String fraction=ProjectionFixtures.payload(ProjectionEventType.ORDER_DETAIL,key).replace("\"quantity\": 2","\"quantity\": 2.5");
        assertThatThrownBy(() -> decoder.decode(ProjectionFixtures.event(ProjectionEventType.ORDER_DETAIL,key,1,UUID.randomUUID(),ProjectionOperation.UPSERT,fraction))).isInstanceOf(InvalidProjectionEvent.class);
        String duplicate="{\"customerId\":\""+key+"\",\"customerId\":\""+key+"\"}";
        assertThatThrownBy(() -> decoder.decode(ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,1,UUID.randomUUID(),ProjectionOperation.UPSERT,duplicate))).isInstanceOf(InvalidProjectionEvent.class);
    }
    @Test void deleteRequiresEmptyObjectAndKeepsIdentityOutsidePayload() {
        UUID key=UUID.randomUUID();var deleted=decoder.decode(ProjectionFixtures.event(ProjectionEventType.PRODUCT_DETAIL,key,2,UUID.randomUUID(),ProjectionOperation.DELETE,"{}"));
        assertThat(deleted.snapshot()).isNull();assertThat(deleted.projectionKey()).isEqualTo(key);
        assertThatThrownBy(() -> decoder.decode(ProjectionFixtures.event(ProjectionEventType.PRODUCT_DETAIL,key,2,UUID.randomUUID(),ProjectionOperation.DELETE,"{\"name\":\"x\"}")))
                .isInstanceOf(InvalidProjectionEvent.class);
    }
}
