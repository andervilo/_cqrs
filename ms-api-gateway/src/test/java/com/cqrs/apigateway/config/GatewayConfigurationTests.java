package com.cqrs.apigateway.config;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class GatewayConfigurationTests {
    private GatewayProperties properties(String publicUrl, String issuer, String audience) {
        return new GatewayProperties(URI.create("http://localhost:8080"), URI.create("http://localhost:8081"),
                URI.create(publicUrl), List.of("http://localhost:4200"), false,
                2000, 5000, 15000, 60000, 1048576, issuer, audience);
    }

    @Test void failsClosedWithoutIdentityAndRejectsMixedProfiles() {
        var config = new SecurityConfiguration();
        var p = properties("https://api.example.com", "", "");
        assertThatThrownBy(() -> config.localGateway(new MockEnvironment(), p))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_ISSUER_URI");
        assertThatThrownBy(() -> config.localGateway(new MockEnvironment().withProperty("spring.profiles.active", "prod"), p))
                .isInstanceOf(IllegalStateException.class);
        var mixed = new MockEnvironment();
        mixed.setActiveProfiles("prod", "local");
        assertThatThrownBy(() -> config.localGateway(mixed, p)).hasMessageContaining("cannot be combined");
        var local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThat(config.localGateway(local, p)).isTrue();
        assertThatThrownBy(() -> config.localGateway(new MockEnvironment(),
                properties("http://api.example.com", "https://issuer.example.com", "cqrs")))
                .hasMessageContaining("HTTPS");
    }

    @Test void acceptsOnlyConfiguredHttpOrigins() {
        assertThat(properties("https://api.example.com", "", "").isValidOrigins()).isTrue();
        for (String invalid : List.of("https://api.example.com/base", "https://user:pass@api.example.com",
                "file:///tmp/file", "https://api.example.com?redirect=x", "https://api.example.com#part"))
            assertThat(properties(invalid, "", "").isValidOrigins()).as(invalid).isFalse();
    }
}
