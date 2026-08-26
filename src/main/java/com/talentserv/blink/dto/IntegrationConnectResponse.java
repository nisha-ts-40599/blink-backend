package com.talentserv.blink.dto;

public record IntegrationConnectResponse(
        boolean connected,
        String provider,
        String account,
        String detail
) {
}
