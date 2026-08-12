package com.example.userservice.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;


@Component
@RequiredArgsConstructor
public class UserEventFactory {

    private final ObjectMapper objectMapper;

    public String build(String eventType, Map<String, Object> payload) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", UUID.randomUUID().toString());
        root.put("eventType", eventType);
        root.put("occurredAt", Instant.now().toString());
        root.put("version", 1);
        root.set("payload", objectMapper.valueToTree(payload));
        return root.toString();
    }
}
