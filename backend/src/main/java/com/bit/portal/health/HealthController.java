package com.bit.portal.health;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "employee-portal");
    }

    @GetMapping("/api/health/db")
    public ResponseEntity<Map<String, String>> databaseHealth() {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT 1");
             var result = statement.executeQuery()) {
            if (result.next() && result.getInt(1) == 1) {
                return ResponseEntity.ok(Map.of("database", "UP"));
            }
        } catch (Exception ignored) {
            // Do not expose connection details or credentials through a public endpoint.
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("database", "DOWN"));
    }
}
