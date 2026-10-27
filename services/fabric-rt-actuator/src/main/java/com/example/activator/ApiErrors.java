package com.example.activator;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    public static class UnconfirmedDeployment extends RuntimeException {
        private final UUID deploymentId;

        public UnconfirmedDeployment(UUID deploymentId, Throwable cause) {
            super("Deployment requires reconciliation", cause);
            this.deploymentId = deploymentId;
        }
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request; inspect the documented schema"));
    }

    @ExceptionHandler(Conflict.class)
    ResponseEntity<Map<String, String>> conflict(Conflict exception) {
        return ResponseEntity.status(409).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(UnconfirmedDeployment.class)
    ResponseEntity<Map<String, String>> unconfirmed(UnconfirmedDeployment exception) {
        LOG.error("Deployment requires reconciliation deploymentId={} type={}",
                exception.deploymentId, exception.getCause().getClass().getSimpleName());
        return ResponseEntity.status(503).body(Map.of("error", exception.getMessage(),
                "deploymentId", exception.deploymentId.toString()));
    }

    @ExceptionHandler({IOException.class, IllegalStateException.class})
    ResponseEntity<Map<String, String>> unavailable(Exception exception) {
        LOG.error("Dependency or persistent storage failure type={}", exception.getClass().getSimpleName());
        return ResponseEntity.status(503).body(Map.of("error", "Operation could not be confirmed; retry safely"));
    }
}
