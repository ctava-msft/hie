package com.example.activator;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private final AppProperties properties;

    public ApiKeyFilter(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                   FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        String expected = path.startsWith("/api/webhooks/")
                ? properties.webhookApiKey() : properties.adminApiKey();
        String supplied = request.getHeader("X-API-Key");
        if (supplied == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
