package com.cqrs.orderquery.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI orderQueryOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Order Query API")
                .description("API de consultas de pedidos")
                .version("v1"));
    }
}
