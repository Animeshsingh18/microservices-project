package com.example.notificationservice.controller;

import com.example.notificationservice.dto.NotificationLogResponse;
import com.example.notificationservice.entity.NotificationLog;
import com.example.notificationservice.repository.NotificationLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Query notification delivery history (requires Bearer token)")
public class NotificationController {

    private final NotificationLogRepository notificationLogRepository;

    @GetMapping("/users/{userId}")
    @Operation(summary = "Get notification history for a user")
    public ResponseEntity<Page<NotificationLogResponse>> getForUser(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<NotificationLog> logs = notificationLogRepository
                .findByUserIdOrderByReceivedAtDesc(userId, PageRequest.of(page, size));

        return ResponseEntity.ok(logs.map(this::toResponse));
    }

    private NotificationLogResponse toResponse(NotificationLog log) {
        return new NotificationLogResponse(
                log.getEventId(), log.getEventType(), log.getUserId(), log.getRecipientEmail(),
                log.getStatus(), log.getReceivedAt(), log.getProcessedAt());
    }
}
