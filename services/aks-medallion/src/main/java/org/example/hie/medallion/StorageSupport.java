package org.example.hie.medallion;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
class StorageSupport {
    private final BlobServiceClient service;
    private final ObjectMapper json;

    StorageSupport(BlobServiceClient service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    BlobClient writeJson(String container, String blobName, Object value) {
        try {
            byte[] bytes = json.writeValueAsBytes(value);
            BlobClient blob = service.getBlobContainerClient(container).getBlobClient(blobName);
            blob.upload(BinaryData.fromBytes(bytes), true);
            blob.setHttpHeaders(new BlobHttpHeaders().setContentType("application/json"));
            return blob;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to commit JSON blob", exception);
        }
    }

    <T> T readJson(String container, String blobName, Class<T> type) {
        try {
            return json.readValue(service.getBlobContainerClient(container)
                    .getBlobClient(blobName).downloadContent().toBytes(), type);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read committed JSON blob", exception);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
