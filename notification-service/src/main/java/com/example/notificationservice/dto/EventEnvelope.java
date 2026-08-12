package com.example.notificationservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;


@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        String eventId,
        String eventType,
        Instant occurredAt,
        int version,
        Map<String, Object> payload
) {}
