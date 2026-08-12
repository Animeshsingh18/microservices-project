package com.example.userservice.config;

import io.nats.client.*;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.time.Duration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class NatsConfig {

    private final NatsProperties props;

    private Connection connection;

    @Bean(destroyMethod = "close")
    public Connection natsConnection() throws Exception {
        Options.Builder builder = new Options.Builder()
                .server(props.getUrl())
                .connectionTimeout(Duration.ofMillis(
                        props.getConnectionTimeoutMs() > 0 ? props.getConnectionTimeoutMs() : 5000))
                .maxReconnects(-1) // reconnect forever
                .reconnectWait(Duration.ofSeconds(2))
                .connectionListener((conn, type) -> log.info("NATS connection event: {}", type))
                .errorListener(new ErrorListener() {
                    @Override
                    public void errorOccurred(Connection conn, String error) {
                        log.error("NATS error: {}", error);
                    }

                    @Override
                    public void exceptionOccurred(Connection conn, Exception exp) {
                        log.error("NATS exception", exp);
                    }
                });

        // --- Auth: prefer creds file (NKey/JWT, recommended for prod) ---
        if (props.getCredsFile() != null && !props.getCredsFile().isBlank()) {
            builder.authHandler(Nats.credentials(props.getCredsFile()));
        } else if (props.getUsername() != null && !props.getUsername().isBlank()) {
            builder.userInfo(props.getUsername(), props.getPassword());
        }

        // --- TLS ---
        if (props.isTlsEnabled()) {
            builder.sslContext(buildSslContext());
        }

        this.connection = Nats.connect(builder.build());
        ensureStreamExists(connection);
        return connection;
    }

    private void ensureStreamExists(Connection nc) throws Exception {
        JetStreamManagement jsm = nc.jetStreamManagement();
        String stream = props.getStreamName();
        try {
            StreamInfo info = jsm.getStreamInfo(stream);
            log.info("JetStream stream '{}' already exists ({} messages)", stream,
                    info.getStreamState().getMsgCount());
        } catch (Exception notFound) {
            StreamConfiguration cfg = StreamConfiguration.builder()
                    .name(stream)
                    .subjects(props.getSubjectPrefix() + ".>")
                    .storageType(StorageType.File)
                    .retentionPolicy(RetentionPolicy.WorkQueue) // consumed once, ack'd = removed
                    .build();
            jsm.addStream(cfg);
            log.info("Created JetStream stream '{}' for subjects '{}.>'", stream, props.getSubjectPrefix());
        }
    }

    private SSLContext buildSslContext() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(props.getTruststorePath())) {
            trustStore.load(fis, props.getTruststorePassword().toCharArray());
        }
        javax.net.ssl.TrustManagerFactory tmf =
                javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (connection != null) {
            connection.close();
        }
    }
}
