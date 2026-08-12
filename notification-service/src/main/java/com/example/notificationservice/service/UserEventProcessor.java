package com.example.notificationservice.service;

import com.example.notificationservice.dto.EventEnvelope;
import com.example.notificationservice.entity.NotificationLog;
import com.example.notificationservice.entity.NotificationStatus;
import com.example.notificationservice.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Handles one inbound event. Idempotent: relies on a unique DB
 * constraint on eventId so that if JetStream redelivers the same
 * message (at-least-once delivery), we detect the duplicate and skip
 * re-sending instead of notifying the user twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserEventProcessor {

    private final NotificationLogRepository notificationLogRepository;
    private final NotificationSender notificationSender;

    /**
     * @return true if the event was handled (successfully OR as a
     *         confirmed duplicate) and the message should be acked;
     *         false if processing failed and the message should be
     *         nak'd / left for redelivery.
     */
    public boolean process(EventEnvelope event) {
        if (event.eventId() == null || event.eventType() == null) {
            log.warn("Received malformed event, missing eventId/eventType: {}", event);
            return true; // ack it — redelivering a malformed message won't fix it
        }

        if (notificationLogRepository.existsByEventId(event.eventId())) {
            log.info("Event {} already processed — skipping duplicate delivery", event.eventId());
            return true;
        }

        Long userId = extractUserId(event);
        String email = extractEmail(event);

        NotificationLog logEntry = NotificationLog.builder()
                .eventId(event.eventId())
                .eventType(event.eventType())
                .userId(userId)
                .recipientEmail(email)
                .build();

        try {
            String subject = subjectFor(event.eventType());
            String body = bodyFor(event);

            if (email != null) {
                notificationSender.send(email, subject, body);
            } else {
                log.warn("Event {} of type {} has no recipient email in payload — logging only",
                        event.eventId(), event.eventType());
            }

            logEntry.setStatus(NotificationStatus.SENT);
            logEntry.setProcessedAt(Instant.now());
            saveLog(logEntry);
            return true;

        } catch (Exception sendFailure) {
            log.error("Failed to send notification for event {}", event.eventId(), sendFailure);
            logEntry.setStatus(NotificationStatus.FAILED);
            logEntry.setErrorDetail(sendFailure.getMessage());
            logEntry.setProcessedAt(Instant.now());
            // We still record the attempt, but return false so the caller
            // nak's the message and JetStream redelivers (up to max-deliver).
            saveLogBestEffort(logEntry);
            return false;
        }
    }

    @Transactional
    protected void saveLog(NotificationLog entry) {
        try {
            notificationLogRepository.save(entry);
        } catch (DataIntegrityViolationException dup) {
            // Race condition: another thread/instance processed the same
            // eventId between our existsBy check and this save. Safe to
            // ignore — the event is still handled exactly once end-to-end.
            log.info("Concurrent duplicate detected for event {} on save — ignoring", entry.getEventId());
        }
    }

    private void saveLogBestEffort(NotificationLog entry) {
        try {
            saveLog(entry);
        } catch (Exception ex) {
            log.error("Could not persist notification log for event {}", entry.getEventId(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Long extractUserId(EventEnvelope event) {
        Object raw = event.payload() != null ? event.payload().get("userId") : null;
        if (raw instanceof Number n) return n.longValue();
        return null;
    }

    private String extractEmail(EventEnvelope event) {
        Object raw = event.payload() != null ? event.payload().get("email") : null;
        return raw != null ? raw.toString() : null;
    }

    private String subjectFor(String eventType) {
        return switch (eventType) {
            case "USER_REGISTERED" -> "Welcome!";
            case "USER_UPDATED" -> "Your profile was updated";
            case "USER_DELETED" -> "Your account was deleted";
            default -> "Notification";
        };
    }

    private String bodyFor(EventEnvelope event) {
        Object name = event.payload() != null ? event.payload().get("name") : null;
        return switch (event.eventType()) {
            case "USER_REGISTERED" -> "Hi " + (name != null ? name : "there") + ", thanks for registering!";
            case "USER_UPDATED" -> "Your profile details were just updated.";
            case "USER_DELETED" -> "Your account has been deleted. Sorry to see you go.";
            default -> "You have a new notification.";
        };
    }
}
