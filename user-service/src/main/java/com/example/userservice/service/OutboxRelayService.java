package com.example.userservice.service;

import com.example.userservice.entity.OutboxEvent;
import com.example.userservice.event.NatsEventPublisher;
import com.example.userservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;


@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayService {

    private final OutboxEventRepository outboxEventRepository;
    private final NatsEventPublisher natsEventPublisher;

    @Value("${app.outbox.max-publish-attempts:5}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${app.outbox.relay-fixed-delay-ms:2000}")
    @Transactional
    public void relayPendingEvents() {
        List<OutboxEvent> batch = outboxEventRepository.findUnpublishedBatch(maxAttempts);
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            boolean success = natsEventPublisher.publish(event.getEventType(), event.getPayload());
            event.setLastAttemptAt(Instant.now());
            event.setAttemptCount(event.getAttemptCount() + 1);

            if (success) {
                event.setPublished(true);
                event.setLastError(null);
            } else {
                event.setLastError("Publish failed at attempt " + event.getAttemptCount());
                if (event.getAttemptCount() >= maxAttempts) {
                    log.error("Outbox event {} exceeded max attempts ({}) — needs manual review",
                            event.getId(), maxAttempts);
                }
            }
            outboxEventRepository.save(event);
        }
    }
}
