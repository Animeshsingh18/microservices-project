package com.example.notificationservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_logs", uniqueConstraints = @UniqueConstraint(columnNames = "eventId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationLog {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true)
    private String eventId; // from the event envelope -> idempotency key

    @Column(nullable = false, length = 80)
    private String eventType;

    private Long userId;

    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Lob
    private String errorDetail;

    @Column(nullable = false, updatable = false)
    private Instant receivedAt;

    private Instant processedAt;

    @PrePersist
    void prePersist() {
        if (this.receivedAt == null) {
            this.receivedAt = Instant.now();
        }
    }
}
