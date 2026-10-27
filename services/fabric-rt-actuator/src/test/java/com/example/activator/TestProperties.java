package com.example.activator;

import java.net.URI;
import java.nio.file.Path;

final class TestProperties {
    static final String WEBHOOK_KEY = "webhook-test-key-with-at-least-32-characters";
    static final String ADMIN_KEY = "admin-test-key-with-at-least-32-characters";

    private TestProperties() {}

    static AppProperties create(Path directory) {
        return new AppProperties(WEBHOOK_KEY, ADMIN_KEY, "test.servicebus.windows.net", "",
                "activator-deliveries", true, 4, URI.create("https://downstream.example.test/events"),
                "", "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222", directory);
    }
}
