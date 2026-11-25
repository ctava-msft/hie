package org.example.hie.medallion;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${app.role}' == 'gold' and '${app.gold-backend:adx}' == 'postgresql-hapi'")
class GoldHapiClient {
    private final HapiVersionedClient client;

    GoldHapiClient(HttpClient http, ObjectMapper json,
                   @Value("${app.hapi-gold-url:}") String url,
                   @Value("${app.hapi-gold-token:}") String token) {
        client = new HapiVersionedClient(http, json, url, token);
    }

    HapiVersionedClient client() {
        return client;
    }
}
