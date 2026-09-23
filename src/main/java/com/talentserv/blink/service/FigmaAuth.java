package com.talentserv.blink.service;

import java.util.Locale;
import java.util.Map;

/** Figma PAT (`figd_…`) must not be sent as Bearer — Figma 401s that combination. */
final class FigmaAuth {

    private FigmaAuth() {
    }

    static Map<String, String> headers(String token) {
        String value = token == null ? "" : token.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("figd_")) {
            return Map.of("X-Figma-Token", value);
        }
        return Map.of("Authorization", "Bearer " + value);
    }
}
