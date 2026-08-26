package com.talentserv.blink.service;

import java.util.Map;

public interface IntegrationHttpGateway {

    IntegrationHttpResponse get(String url, Map<String, String> headers);

    default IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
        throw new UnsupportedOperationException("POST is not supported.");
    }

    record IntegrationHttpResponse(int status, String body) {
    }
}
