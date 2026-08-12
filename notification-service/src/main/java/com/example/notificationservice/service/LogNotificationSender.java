package com.example.notificationservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.notifications", name = "channel", havingValue = "LOG", matchIfMissing = true)
@Slf4j
public class LogNotificationSender implements NotificationSender {
    @Override
    public void send(String recipient, String subject, String body) {
        log.info("[SIMULATED NOTIFICATION] to={} subject='{}' body='{}'", recipient, subject, body);
    }
}
