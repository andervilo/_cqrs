package com.cqrs.ordercommand.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI orderCommandOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Order Command API")
                .description("API de comandos de pedidos")
                .version("v1"));
    }
}
