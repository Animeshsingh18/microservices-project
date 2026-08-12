package com.example.apigateway.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@RestController
public class FallbackController {

    @RequestMapping("/fallback/user-service")
    public Mono<ResponseEntity<Map<String, Object>>> userServiceFallback() {
        return fallbackResponse("User Service is temporarily unavailable. Please try again shortly.");
    }

    @RequestMapping("/fallback/notification-service")
    public Mono<ResponseEntity<Map<String, Object>>> notificationServiceFallback() {
        return fallbackResponse("Notification Service is temporarily unavailable. Please try again shortly.");
    }

    private Mono<ResponseEntity<Map<String, Object>>> fallbackResponse(String message) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", 503,
                "error", "Service Unavailable",
                "message", message
        );
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body));
    }
}
