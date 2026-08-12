package com.example.apigateway.filter;

import com.example.apigateway.config.JwtProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Applied per-route in application.yml as a "JwtAuth" filter.
 *
 * Responsibilities:
 *  - Reject requests with no/invalid/expired Bearer token at the edge
 *    (401), so User Service and Notification Service are never even
 *    reached with a bad token.
 *  - On success, strip the client-supplied Authorization header and
 *    forward trusted identity via X-User-Id / X-User-Email headers, so
 *    downstream services don't have to re-parse the token themselves
 *    (they still CAN independently validate the JWT too — belt and
 *    braces — but the gateway is the primary checkpoint per the HLD).
 *
 * Config supports an optional "required" flag (default true) in case a
 * route ever needs "validate if present, don't reject if absent".
 */
@Component
@Slf4j
public class JwtAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthGatewayFilterFactory.Config> {

    private final JwtProperties jwtProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtAuthGatewayFilterFactory(JwtProperties jwtProperties) {
        super(Config.class);
        this.jwtProperties = jwtProperties;
    }

    public static class Config {
        private boolean required = true;

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String header = request.getHeaders().getFirst("Authorization");

            if (header == null || !header.startsWith("Bearer ")) {
                if (!config.isRequired()) {
                    return chain.filter(exchange);
                }
                return unauthorized(exchange, "Missing or malformed Authorization header");
            }

            String token = header.substring(7);

            try {
                Claims claims = Jwts.parser()
                        .verifyWith(key())
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                String userId = claims.getSubject();
                String email = claims.get("email", String.class);

                ServerHttpRequest mutatedRequest = request.mutate()
                        .headers(httpHeaders -> {
                            httpHeaders.remove("Authorization");
                            httpHeaders.set("X-User-Id", userId);
                            if (email != null) {
                                httpHeaders.set("X-User-Email", email);
                            }
                            // Re-add Authorization so downstream services'
                            // own JWT filters (defense-in-depth) still work
                            // if called directly / during local dev.
                            httpHeaders.set("Authorization", header);
                        })
                        .build();

                return chain.filter(exchange.mutate().request(mutatedRequest).build());

            } catch (ExpiredJwtException ex) {
                log.debug("Rejected expired token at gateway");
                return unauthorized(exchange, "Token expired");
            } catch (JwtException | IllegalArgumentException ex) {
                log.debug("Rejected invalid token at gateway: {}", ex.getMessage());
                return unauthorized(exchange, "Invalid token");
            }
        };
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", 401,
                "error", "Unauthorized",
                "message", message
        );

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = ("{\"error\":\"Unauthorized\"}").getBytes(StandardCharsets.UTF_8);
        }

        var buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
