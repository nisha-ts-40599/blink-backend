package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FigmaAuthTest {

    @Test
    void personalAccessTokenUsesXFigmaTokenOnly() {
        var headers = FigmaAuth.headers("figd_abc123");
        assertThat(headers).containsEntry("X-Figma-Token", "figd_abc123");
        assertThat(headers).doesNotContainKey("Authorization");
    }

    @Test
    void oauthTokenUsesBearerOnly() {
        var headers = FigmaAuth.headers("oauth-access-token");
        assertThat(headers).containsEntry("Authorization", "Bearer oauth-access-token");
        assertThat(headers).doesNotContainKey("X-Figma-Token");
    }
}
