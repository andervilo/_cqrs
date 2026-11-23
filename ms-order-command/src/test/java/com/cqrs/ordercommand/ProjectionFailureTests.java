package com.cqrs.ordercommand;

import com.cqrs.ordercommand.application.dto.ProductDtos;
import com.cqrs.ordercommand.application.service.ProductService;
import com.cqrs.ordercommand.application.projection.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="outbox.relay.enabled=false")
class ProjectionFailureTests {
    @Autowired ProductService products;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ProjectionPayloadSerializer serializer;
    @MockitoSpyBean ProductProjectionBuilder builder;

    @Test
    void serializationFailureRollsBackBusinessRevisionsAndAllEvents() {
        var product=products.create(new ProductDtos.Create(UUID.randomUUID().toString(),"Antes",null,BigDecimal.ONE));
        doThrow(new UnsupportedOperationException("serialization failure")).when(serializer).serialize(any());
        assertThatThrownBy(() -> products.update(product.id(),new ProductDtos.Update(product.sku(),"Depois",null,BigDecimal.TEN,true)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertUnchanged(product.id());
    }
    @Test
    void snapshotCalculationFailureRollsBackRevisionsAndBusiness() {
        var product=products.create(new ProductDtos.Create(UUID.randomUUID().toString(),"Antes",null,BigDecimal.ONE));
        doThrow(new UnsupportedOperationException("calculation failure")).when(builder).build(product.id());
        assertThatThrownBy(() -> products.update(product.id(),new ProductDtos.Update(product.sku(),"Depois",null,BigDecimal.TEN,true)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertUnchanged(product.id());
    }
    private void assertUnchanged(UUID id) {
        assertThat(products.get(id).name()).isEqualTo("Antes");
        assertThat(jdbc.queryForObject("SELECT max(version) FROM order_command.projection_revision WHERE projection_key=?",Long.class,id.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox WHERE projection_key=?",Integer.class,id.toString())).isEqualTo(2);
    }
}
