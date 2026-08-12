package com.example.notificationservice.listener;

import com.example.notificationservice.config.NatsProperties;
import com.example.notificationservice.dto.EventEnvelope;
import com.example.notificationservice.service.UserEventProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.*;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventListener {

    private final Connection natsConnection;
    private final NatsProperties props;
    private final UserEventProcessor processor;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nats-user-event-consumer");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running.set(true);
        executor.submit(this::consumeLoop);
        log.info("Started durable JetStream consumer '{}' on subjects '{}.>'",
                props.getDurableConsumerName(), props.getSubjectPrefix());
    }

    private void consumeLoop() {
        try {
            JetStreamManagement jsm = natsConnection.jetStreamManagement();
            ensureDurableConsumer(jsm);

            JetStream js = natsConnection.jetStream();
            PullSubscribeOptions pullOptions = PullSubscribeOptions.builder()
                    .stream(props.getStreamName())
                    .durable(props.getDurableConsumerName())
                    .build();

            JetStreamSubscription sub = js.subscribe(
                    props.getSubjectPrefix() + ".>", pullOptions);

            while (running.get()) {
                try {
                    List<Message> messages = sub.fetch(10, Duration.ofSeconds(5));
                    for (Message msg : messages) {
                        handleMessage(msg);
                    }
                } catch (Exception loopError) {
                    log.error("Error while fetching/processing messages, backing off", loopError);
                    sleepQuietly(2000);
                }
            }
        } catch (Exception fatal) {
            log.error("Fatal error setting up JetStream consumer — notification listener is DOWN", fatal);
        }
    }

    private void handleMessage(Message msg) {
        long deliveredCount = msg.metaData().deliveredCount();

        try {
            EventEnvelope event = objectMapper.readValue(msg.getData(), EventEnvelope.class);

            if (deliveredCount > props.getMaxDeliver()) {
                deadLetter(msg, event, "exceeded max deliver attempts (" + props.getMaxDeliver() + ")");
                return;
            }

            boolean handled = processor.process(event);
            if (handled) {
                msg.ack();
            } else {

                msg.nakWithDelay(Duration.ofSeconds(5));
            }
        } catch (Exception parseError) {
            log.error("Could not parse/process message (delivery #{}), sending to DLQ",
                    deliveredCount, parseError);
            deadLetterRaw(msg, parseError.getMessage());
        }
    }

    private void deadLetter(Message msg, EventEnvelope event, String reason) {
        try {
            String dlqPayload = new String(msg.getData(), StandardCharsets.UTF_8);
            natsConnection.publish(props.getDlqSubject(), dlqPayload.getBytes(StandardCharsets.UTF_8));
            log.error("Dead-lettered event {} ({}): {}", event != null ? event.eventId() : "?", reason, dlqPayload);
        } finally {
            msg.ack();
        }
    }

    private void deadLetterRaw(Message msg, String reason) {
        try {
            natsConnection.publish(props.getDlqSubject(), msg.getData());
            log.error("Dead-lettered unparseable message: {}", reason);
        } finally {
            msg.ack();
        }
    }

    private void ensureDurableConsumer(JetStreamManagement jsm) throws Exception {
        try {
            jsm.getConsumerInfo(props.getStreamName(), props.getDurableConsumerName());
            log.info("Durable consumer '{}' already exists", props.getDurableConsumerName());
        } catch (Exception notFound) {
            ConsumerConfiguration cc = ConsumerConfiguration.builder()
                    .durable(props.getDurableConsumerName())
                    .filterSubject(props.getSubjectPrefix() + ".>")
                    .ackPolicy(AckPolicy.Explicit)
                    .ackWait(Duration.ofSeconds(props.getAckWaitSeconds() > 0 ? props.getAckWaitSeconds() : 30))
                    .maxDeliver(props.getMaxDeliver() > 0 ? props.getMaxDeliver() : 5)
                    .deliverPolicy(DeliverPolicy.All)
                    .build();
            jsm.addOrUpdateConsumer(props.getStreamName(), cc);
            log.info("Created durable consumer '{}' on stream '{}'",
                    props.getDurableConsumerName(), props.getStreamName());
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdown();
    }
}
