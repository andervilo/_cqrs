package com.cqrs.apigateway.config;

import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @NotNull URI commandServiceUrl, @NotNull URI queryServiceUrl, @NotNull URI publicUrl,
        @NotEmpty List<String> corsAllowedOrigins, boolean maintenanceEnabled,
        @Positive int connectTimeoutMs, @Positive int queryTimeoutMs,
        @Positive int commandTimeoutMs, @Positive int rebuildTimeoutMs,
        @Min(1) @Max(16777216) int maxRequestBytes, String jwtIssuerUri, String jwtAudience) {

    @AssertTrue(message = "service/public URLs must be HTTP(S) origins without credentials, path, query or fragment")
    public boolean isValidOrigins() {
        return origin(commandServiceUrl) && origin(queryServiceUrl) && origin(publicUrl);
    }

    private static boolean origin(URI value) {
        return value != null && List.of("http", "https").contains(value.getScheme())
                && value.getHost() != null && value.getUserInfo() == null
                && (value.getRawPath() == null || value.getRawPath().isEmpty())
                && value.getQuery() == null && value.getFragment() == null;
    }
}
