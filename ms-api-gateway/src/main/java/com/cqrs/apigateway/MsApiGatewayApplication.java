package com.cqrs.apigateway;

import com.cqrs.apigateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class MsApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(MsApiGatewayApplication.class, args);
    }
}
