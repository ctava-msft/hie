package com.example.activator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DeploymentStore {
    private final Path directory;
    private final ObjectMapper mapper;

    public DeploymentStore(AppProperties properties, ObjectMapper mapper) throws IOException {
        directory = properties.auditDirectory().toAbsolutePath();
        Files.createDirectories(directory);
        this.mapper = mapper;
    }

    public synchronized <T> T exclusively(IOOperation<T> operation) throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve("deployment.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                throw new ApiErrors.Conflict("Another deployment is in progress");
            }
            return operation.run();
        }
    }

    public void save(UUID id, String suffix, JsonNode value) throws IOException {
        Path target = directory.resolve(id + suffix + ".json");
        Path temporary = Files.createTempFile(directory, "pending-", ".json");
        try {
            byte[] bytes = mapper.writeValueAsBytes(value);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public JsonNode previous(UUID id) throws IOException {
        Path file = directory.resolve(id + "-before.json");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Unknown rollback reference");
        }
        return mapper.readTree(Files.readAllBytes(file));
    }

    @FunctionalInterface
    public interface IOOperation<T> {
        T run() throws IOException;
    }
}
