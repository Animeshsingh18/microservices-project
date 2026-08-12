package com.example.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 50)
    private String aggregateType; // e.g. "USER"

    @Column(nullable = false)
    private String aggregateId;   // e.g. user id as string

    @Column(nullable = false, length = 80)
    private String eventType;     // e.g. "USER_REGISTERED"

    @Lob
    @Column(nullable = false)
    private String payload;       // JSON string

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Builder.Default
    private boolean published = false;

    @Builder.Default
    private int attemptCount = 0;

    private Instant lastAttemptAt;

    private String lastError;

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
