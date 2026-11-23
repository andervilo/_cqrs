package com.cqrs.ordercommand;

import java.util.UUID;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class ProjectionMigrationTests {
    @Test
    void preservesExistingLegacyEventsAndCreatesEmptyStrictProjectionOutbox() throws Exception {
        try(var postgres=new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            var config=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                    .defaultSchema("order_command").locations("classpath:db/migration");
            config.target("2").load().migrate();
            UUID customer=UUID.randomUUID(),product=UUID.randomUUID(),order=UUID.randomUUID(),item=UUID.randomUUID(),event=UUID.randomUUID();
            try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())) {
                connection.setAutoCommit(false);
                try(var sql=connection.createStatement()) {
                    sql.execute("SET search_path TO order_command");
                    sql.execute("INSERT INTO customer(id,name,email) VALUES ('"+customer+"','Legacy','legacy@example.com')");
                    sql.execute("INSERT INTO product(id,sku,name,unit_price) VALUES ('"+product+"','LEGACY','Legacy',1)");
                    sql.execute("INSERT INTO orders(id,customer_id,status,total_amount) VALUES ('"+order+"','"+customer+"','CREATED',1)");
                    sql.execute("INSERT INTO item(id,order_id,product_id,product_sku,product_name,quantity,unit_price) VALUES ('"+item+"','"+order+"','"+product+"','LEGACY','Legacy',1,1)");
                    sql.execute("INSERT INTO order_outbox(id,order_id,status) VALUES ('"+event+"','"+order+"','ERRO')");
                    connection.commit();
                }
            }
            config.target("latest").load().migrate();
            try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var sql=connection.createStatement()) {
                try(var result=sql.executeQuery("SELECT id,status FROM order_command.order_outbox_legacy")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject(1,UUID.class)).isEqualTo(event);
                    assertThat(result.getString(2)).isEqualTo("ERRO");
                    assertThat(result.next()).isFalse();
                }
                try(var result=sql.executeQuery("SELECT count(*) FROM order_command.order_outbox")) {
                    result.next(); assertThat(result.getInt(1)).isZero();
                }
                assertThatThrownBy(() -> sql.execute("INSERT INTO order_command.order_outbox(id,data_criacao) VALUES ('"+UUID.randomUUID()+"',now())"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }
}
