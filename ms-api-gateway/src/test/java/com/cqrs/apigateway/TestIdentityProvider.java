package com.cqrs.apigateway;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

final class TestIdentityProvider implements AutoCloseable {
    private final RSAKey key;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    TestIdentityProvider() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                String json = exchange.getRequestURI().getPath().equals("/jwks")
                        ? new JWKSet(key.toPublicJWK()).toString()
                        : "{\"issuer\":\"" + issuer() + "\",\"jwks_uri\":\"" + issuer()
                        + "/jwks\",\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    String issuer() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    String token(String scope) { return token(scope, issuer(), "cqrs-api", Instant.now().plusSeconds(300), key); }
    String token(String scope, String issuer, String audience, Instant expiry, RSAKey signer) {
        try {
            var claims = new JWTClaimsSet.Builder().subject("operator").issuer(issuer).audience(audience)
                    .issueTime(Date.from(Instant.now())).expirationTime(Date.from(expiry))
                    .claim("scope", scope).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
            jwt.sign(new RSASSASigner(signer));
            return jwt.serialize();
        } catch (JOSEException e) { throw new IllegalStateException(e); }
    }
    RSAKey key() { return key; }
    @Override public void close() { server.stop(0); executor.close(); }
}
