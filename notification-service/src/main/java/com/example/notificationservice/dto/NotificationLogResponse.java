package com.example.notificationservice.dto;

import com.example.notificationservice.entity.NotificationStatus;

import java.time.Instant;

public record NotificationLogResponse(
        String eventId,
        String eventType,
        Long userId,
        String recipientEmail,
        NotificationStatus status,
        Instant receivedAt,
        Instant processedAt
) {}
