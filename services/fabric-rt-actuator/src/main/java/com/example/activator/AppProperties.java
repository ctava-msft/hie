package com.example.activator;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app")
public record AppProperties(
        @NotBlank @Size(min = 32) String webhookApiKey,
        @NotBlank @Size(min = 32) String adminApiKey,
        String serviceBusNamespace,
        String serviceBusConnectionString,
        @NotBlank String serviceBusQueue,
        boolean workerEnabled,
        @Min(1) @Max(32) int workerConcurrency,
        URI downstreamUrl,
        String downstreamBearerToken,
        String fabricWorkspaceId,
        String fabricActivatorId,
        Path auditDirectory) {

    @AssertTrue(message = "API keys must be distinct")
    public boolean isDistinctKeys() {
        return webhookApiKey != null && !webhookApiKey.equals(adminApiKey);
    }

    @AssertTrue(message = "Set SERVICE_BUS_NAMESPACE or SERVICE_BUS_CONNECTION_STRING")
    public boolean isBrokerConfigured() {
        return (serviceBusNamespace != null && !serviceBusNamespace.isBlank())
                || (serviceBusConnectionString != null && !serviceBusConnectionString.isBlank());
    }

    @AssertTrue(message = "DOWNSTREAM_URL must use HTTPS without credentials or a fragment")
    public boolean isDownstreamSecure() {
        return downstreamUrl != null && "https".equals(downstreamUrl.getScheme())
                && downstreamUrl.getHost() != null && downstreamUrl.getUserInfo() == null
                && downstreamUrl.getFragment() == null;
    }
}
