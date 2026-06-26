package com.xspaceagi.ctl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RestController
@Slf4j
public class HealthController {

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(2);

    private final Environment environment;

    public HealthController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("success");
    }

    @GetMapping("/ready")
    public ResponseEntity<String> ready() {
        List<String> errors = new ArrayList<>();
        checkTcp("mysql", getProperty("DB_HOST", "localhost"), getIntProperty("DB_PORT", getIntProperty("MYSQL_PORT", 3306)), errors);
        checkTcp("redis", getProperty("REDIS_HOST", "localhost"), getIntProperty("REDIS_PORT", 6379), errors);
        checkMilvus(errors);
        if (errors.isEmpty()) {
            return ResponseEntity.ok("success");
        }
        String message = String.join("; ", errors);
        log.warn("Readiness check failed: {}", message);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(message);
    }

    private void checkMilvus(List<String> errors) {
        String milvusUri = getProperty("MILVUS_URI", "http://localhost:19530");
        try {
            URI uri = URI.create(milvusUri);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 19530;
            checkTcp("milvus", host, port, errors);
        } catch (Exception e) {
            errors.add("milvus uri invalid: " + e.getMessage());
        }
    }

    private void checkTcp(String name, String host, int port, List<String> errors) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), (int) CHECK_TIMEOUT.toMillis());
        } catch (Exception e) {
            errors.add(name + " unavailable at " + host + ":" + port);
        }
    }

    private String getProperty(String envName, String defaultValue) {
        String value = environment.getProperty(envName);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return switch (envName) {
            case "DB_HOST" -> firstPresent("MYSQL_HOST", "spring.datasource.dynamic.datasource.master.host", defaultValue);
            case "REDIS_HOST" -> environment.getProperty("spring.data.redis.host", defaultValue);
            case "MILVUS_URI" -> environment.getProperty("milvus.uri", defaultValue);
            default -> defaultValue;
        };
    }

    private String firstPresent(String firstName, String secondName, String defaultValue) {
        String firstValue = environment.getProperty(firstName);
        if (firstValue != null && !firstValue.isBlank()) {
            return firstValue;
        }
        String secondValue = environment.getProperty(secondName);
        if (secondValue != null && !secondValue.isBlank()) {
            return secondValue;
        }
        return defaultValue;
    }

    private int getIntProperty(String envName, int defaultValue) {
        String value = environment.getProperty(envName);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
