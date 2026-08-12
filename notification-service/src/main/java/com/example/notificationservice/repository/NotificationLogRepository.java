package com.example.notificationservice.repository;

import com.example.notificationservice.entity.NotificationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {
    Optional<NotificationLog> findByEventId(String eventId);
    boolean existsByEventId(String eventId);
    Page<NotificationLog> findByUserIdOrderByReceivedAtDesc(Long userId, Pageable pageable);
}
