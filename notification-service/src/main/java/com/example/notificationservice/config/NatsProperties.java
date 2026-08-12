package com.example.notificationservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.nats")
@Getter
@Setter
public class NatsProperties {
    private String url;
    private String credsFile;
    private String username;
    private String password;
    private boolean tlsEnabled;
    private String truststorePath;
    private String truststorePassword;
    private String streamName;
    private String subjectPrefix;
    private String durableConsumerName;
    private int maxDeliver;
    private int ackWaitSeconds;
    private long connectionTimeoutMs;
    private String dlqSubject;
}
