package com.cqrs.ordercommand;

import com.cqrs.ordercommand.application.dto.CustomerDtos;
import com.cqrs.ordercommand.application.service.CustomerService;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "outbox.relay.enabled=true","outbox.relay.topic=relay-scheduled-test.v1",
    "outbox.relay.poll-interval-ms=100","outbox.relay.initial-delay-ms=50"
})
class ScheduledRelayTests {
    @Autowired CustomerService customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConsumerFactory<String,String> consumers;
    @Test
    void scheduledPollPublishesCommandProjectionsWithoutManualInvocation() {
        var customer=customers.create(new CustomerDtos.Create("Cliente",UUID.randomUUID()+"@example.com",null));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox WHERE projection_key=? AND status='SENDED'",Integer.class,customer.id().toString())).isEqualTo(2));
        var types=new HashSet<String>();
        try(var consumer=consumers.createConsumer("scheduled-test-"+UUID.randomUUID(),"scheduled")) {
            consumer.subscribe(List.of("relay-scheduled-test.v1"));
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(types.size()<2 && System.nanoTime()<deadline) {
                for(var record:consumer.poll(Duration.ofMillis(500))) {
                    if(record.key().endsWith(":"+customer.id())) {
                        types.add(new String(record.headers().lastHeader("eventType").value(),java.nio.charset.StandardCharsets.UTF_8));
                        assertThat(record.value()).contains(customer.id().toString()).doesNotContain("\"data\":");
                    }
                }
            }
        }
        assertThat(types).containsExactlyInAnyOrder("CUSTOMER_SUMMARY","CUSTOMER_DETAIL");
    }
}
