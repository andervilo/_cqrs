package com.cqrs.apigateway.config;

import com.cqrs.apigateway.error.ProblemWriter;
import java.util.Arrays;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    boolean localGateway(Environment environment, GatewayProperties properties) {
        boolean local = Arrays.asList(environment.getActiveProfiles()).contains("local");
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (local && prod) throw new IllegalStateException("local and prod cannot be combined");
        if (!local) {
            if (properties.jwtIssuerUri().isBlank() || properties.jwtAudience().isBlank())
                throw new IllegalStateException("JWT_ISSUER_URI and JWT_AUDIENCE are required outside local profile");
            if (!properties.publicUrl().getScheme().equals("https"))
                throw new IllegalStateException("GATEWAY_PUBLIC_URL must use HTTPS outside local profile");
        }
        return local;
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http, boolean localGateway,
                                     GatewayProperties p, ProblemWriter writer) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, error) -> {
                            exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
                            return writer.write(exchange, HttpStatus.UNAUTHORIZED, "Token Bearer válido é obrigatório.");
                        })
                        .accessDeniedHandler((exchange, error) -> writer.write(exchange,
                                HttpStatus.FORBIDDEN, "Permissão insuficiente para esta operação.")));
        http.authorizeExchange(auth -> auth
                .pathMatchers("/actuator/health").permitAll()
                .anyExchange().access((authentication, context) -> {
                    var exchange = context.getExchange();
                    var endpoint = exchange.<EndpointCatalog.Endpoint>getAttribute("endpoint");
                    String path = exchange.getRequest().getPath().value();
                    if (localGateway) return Mono.just(new AuthorizationDecision(true));
                    // Detailed management endpoints exist only on the dedicated listener in production.
                    var environment = exchange.getApplicationContext().getEnvironment();
                    String managementPort = environment.getProperty("local.management.port");
                    if (path.startsWith("/actuator/") && managementPort != null
                            && environment.getProperty("management.server.port") != null
                            && !managementPort.equals(environment.getProperty("local.server.port"))
                            && exchange.getRequest().getLocalAddress() != null
                            && exchange.getRequest().getLocalAddress().getPort() == Integer.parseInt(managementPort))
                        return Mono.just(new AuthorizationDecision(true));
                    String scope = endpoint != null ? endpoint.scope() : "cqrs.docs";
                    boolean docs = path.equals("/swagger-ui.html") || path.startsWith("/swagger-ui/")
                            || path.startsWith("/webjars/swagger-ui/") || path.startsWith("/v3/api-docs");
                    return authentication.<AuthorizationResult>map(a -> new AuthorizationDecision(a.isAuthenticated()
                            && (endpoint != null || docs) && a.getAuthorities().stream()
                            .anyMatch(g -> g.getAuthority().equals("SCOPE_" + scope))))
                            .defaultIfEmpty(new AuthorizationDecision(false));
                }));
        if (!localGateway) {
            var decoder = NimbusReactiveJwtDecoder.withIssuerLocation(p.jwtIssuerUri()).build();
            OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(p.jwtAudience())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(p.jwtIssuerUri()), audience));
            http.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtDecoder(decoder))
                    .authenticationEntryPoint((exchange, error) -> {
                        exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                        return writer.write(exchange, HttpStatus.UNAUTHORIZED, "Token Bearer inválido ou expirado.");
                    }));
        }
        return http.build();
    }
}
