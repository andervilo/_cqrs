package com.cqrs.orderquery;

import com.cqrs.orderquery.application.dashboard.*;
import com.cqrs.orderquery.application.inbox.*;
import com.cqrs.orderquery.domain.ProjectionEventType;
import com.cqrs.orderquery.infrastructure.messaging.ProjectionEventDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardContractTests {
    static final ObjectMapper json=JsonMapper.builder().build();
    static ValidatorFactory validation;
    ProjectionEventDecoder decoder;
    @BeforeAll static void validation(){validation=Validation.buildDefaultValidatorFactory();}
    @AfterAll static void close(){validation.close();}
    @BeforeEach void setup(){
        var handler=mock(ProjectionHandler.class);
        when(handler.type()).thenReturn(ProjectionEventType.SALES_DASHBOARD);
        doReturn(DashboardSnapshot.class).when(handler).payloadType();
        decoder=new ProjectionEventDecoder(json,validation.getValidator(),new ProjectionHandlerRegistry(List.of(handler)));
    }
    static String fixture(String name){
        try(var stream=DashboardContractTests.class.getResourceAsStream("/contracts/"+name+".json")){
            if(stream==null)throw new IllegalStateException("Missing complete producer fixture: "+name);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    void decode(String payload){decoder.decode(ProjectionFixtures.event(ProjectionEventType.SALES_DASHBOARD,
            DashboardPeriod.TODAY.id(),1,UUID.randomUUID(),com.cqrs.orderquery.domain.ProjectionOperation.UPSERT,payload));}
    void rejects(Consumer<ObjectNode> mutate){
        ObjectNode tree=(ObjectNode)json.readTree(fixture("dashboard-today"));mutate.accept(tree);
        assertThatThrownBy(()->decode(json.writeValueAsString(tree))).isInstanceOf(InvalidProjectionEvent.class);
    }
    @Test void acceptsCompleteRealProducerFixturesIncludingEmptyAndZeroBaseMetrics(){
        decode(fixture("dashboard-today"));decode(fixture("dashboard-empty"));
    }
    @Test void requiresCompleteNestedContractAndRejectsUnknownFieldsAndNullPrimitives(){
        rejects(t->t.put("orderId",UUID.randomUUID().toString()));
        rejects(t->((ObjectNode)t.path("orders")).remove("retainedOrderValue"));
        rejects(t->((ObjectNode)t.path("orders")).putNull("orderCount"));
        rejects(t->((ObjectNode)t.path("orders")).put("orderCount",100.5));
        rejects(t->((ObjectNode)t.path("orders")).put("retainedOrderValue","45000.00"));
        rejects(t->t.put("currency","USD"));
        rejects(t->t.put("periodCode","LAST_7_DAYS"));
    }
    @Test void rejectsContradictoryCardsSeriesRankingsAndLinks(){
        rejects(t->((ObjectNode)t.path("orders")).put("requestedValue",1));
        rejects(t->((ObjectNode)t.path("summary").path("orderCount")).put("changePercent",999));
        rejects(t->((ObjectNode)t.path("daily").get(0)).put("orderCount",999));
        rejects(t->((ObjectNode)t.path("products")).put("otherProductsRetainedValue",999));
        rejects(t->((ObjectNode)t.path("insights").get(0).path("drillDown")).put("path","https://evil.example/data"));
        rejects(t->((ObjectNode)t.path("insights").get(0).path("drillDown").path("query")).put("sql","DROP TABLE orders"));
        rejects(t->((ObjectNode)t.path("unavailableMetrics").get(0)).put("value",0));
    }
}
