package com.talentserv.blink.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        return send(url, headers, "GET", null);
    }

    @Override
    public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
        return send(url, headers, "POST", jsonBody == null ? "{}" : jsonBody);
    }

    private IntegrationHttpResponse send(String url, Map<String, String> headers, String method, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "BLINK-Integrator")
                .header("Accept", "application/json");
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            builder.GET();
        }
        headers.forEach(builder::header);
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
