package com.example.notificationservice.config;

import io.nats.client.*;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.time.Duration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class NatsConnectionConfig {

    private final NatsProperties props;
    private Connection connection;

    @Bean(destroyMethod = "close")
    public Connection natsConnection() throws Exception {
        Options.Builder builder = new Options.Builder()
                .server(props.getUrl())
                .connectionTimeout(Duration.ofMillis(
                        props.getConnectionTimeoutMs() > 0 ? props.getConnectionTimeoutMs() : 5000))
                .maxReconnects(-1)
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

        if (props.getCredsFile() != null && !props.getCredsFile().isBlank()) {
            builder.authHandler(Nats.credentials(props.getCredsFile()));
        } else if (props.getUsername() != null && !props.getUsername().isBlank()) {
            builder.userInfo(props.getUsername(), props.getPassword());
        }

        if (props.isTlsEnabled()) {
            builder.sslContext(buildSslContext());
        }

        this.connection = Nats.connect(builder.build());
        return connection;
    }

    private SSLContext buildSslContext() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(props.getTruststorePath())) {
            trustStore.load(fis, props.getTruststorePassword().toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
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
