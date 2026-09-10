package com.talentserv.blink.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.talentserv.blink.error.ApiException;

@Component
public class DefaultIntegrationHttpGateway implements IntegrationHttpGateway {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    @Override
    public IntegrationHttpResponse get(String url, Map<String, String> headers) {
        return send(url, headers, "GET", null, null);
    }

    @Override
    public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
        return post(url, headers, jsonBody, "application/json");
    }

    @Override
    public IntegrationHttpResponse post(String url, Map<String, String> headers, String body, String contentType) {
        return send(url, headers, "POST", body == null ? "" : body, contentType);
    }

    @Override
    public IntegrationHttpResponse put(String url, Map<String, String> headers, String jsonBody) {
        return send(url, headers, "PUT", jsonBody == null ? "{}" : jsonBody, "application/json");
    }

    private IntegrationHttpResponse send(
            String url,
            Map<String, String> headers,
            String method,
            String body,
            String contentType
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .setHeader("User-Agent", "BLINK-Integrator")
                .setHeader("Accept", "application/json");
        if ("POST".equals(method) || "PUT".equals(method)) {
            byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            var publisher = HttpRequest.BodyPublishers.ofByteArray(bytes);
            if ("PUT".equals(method)) {
                builder.PUT(publisher);
            } else {
                builder.POST(publisher);
            }
            // Set Content-Type after the body publisher so Java HttpClient cannot
            // rewrite it to text/plain (that is what Atlassian rejects as 415).
            builder.setHeader("Content-Type", contentType == null || contentType.isBlank() ? "application/json" : contentType);
        } else {
            builder.GET();
        }
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && value != null && !name.equalsIgnoreCase("Content-Type")) {
                    builder.setHeader(name, value);
                }
            });
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new IntegrationHttpResponse(response.statusCode(), response.body() == null ? "" : response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "Connection timed out.");
        } catch (IOException | IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach the provider.");
        }
    }
}
