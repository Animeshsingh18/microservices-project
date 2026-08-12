package com.example.userservice.event;

import com.example.userservice.config.NatsProperties;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.api.PublishAck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class NatsEventPublisher {

    private final Connection natsConnection;
    private final NatsProperties natsProperties;


    public boolean publish(String eventType, String jsonPayload) {
        String subject = natsProperties.getSubjectPrefix() + "." + eventType.toLowerCase();
        try {
            JetStream js = natsConnection.jetStream();
            PublishAck ack = js.publish(subject, jsonPayload.getBytes(StandardCharsets.UTF_8));
            if (ack.hasError()) {
                log.error("JetStream publish rejected for subject {}: {}", subject, ack.getError());
                return false;
            }
            log.info("Published event to subject '{}' (stream seq {})", subject, ack.getSeqno());
            return true;
        } catch (Exception ex) {
            log.error("Failed to publish event to subject '{}'", subject, ex);
            return false;
        }
    }
}
