package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OAuthRedirectResolverTest {

    private static final String LOCAL =
            "http://localhost:5173/api/integrations/github/oauth/callback";
    private static final String RENDER =
            "https://blink-backend-af7x.onrender.com/api/integrations/github/oauth/callback";
    private static final String RENDER_BASE = "https://blink-backend-af7x.onrender.com";
    private static final String CORS = "http://localhost:5173,https://blink-ui.onrender.com";

    @Test
    void usesRequestedPublicCallbackAndIgnoresLocalhostConfig() {
        String resolved = OAuthRedirectResolver.resolve(
                "github",
                RENDER,
                RENDER_BASE,
                LOCAL,
                CORS
        );
        assertThat(resolved).isEqualTo(RENDER);
    }

    @Test
    void derivesCallbackFromPublicApiBaseWhenRequestOmitsIt() {
        String resolved = OAuthRedirectResolver.resolve(
                "github",
                null,
                RENDER_BASE,
                LOCAL,
                CORS
        );
        assertThat(resolved).isEqualTo(RENDER);
    }

    @Test
    void rejectsForeignRedirectAndFallsBackToPublicApi() {
        String resolved = OAuthRedirectResolver.resolve(
                "github",
                "https://evil.example/api/integrations/github/oauth/callback",
                RENDER_BASE,
                LOCAL,
                CORS
        );
        assertThat(resolved).isEqualTo(RENDER);
    }

    @Test
    void allowsFrontendOriginWhenListedInCors() {
        String spa = "https://blink-ui.onrender.com/api/integrations/github/oauth/callback";
        String resolved = OAuthRedirectResolver.resolve(
                "github",
                spa,
                RENDER_BASE,
                "",
                CORS
        );
        assertThat(resolved).isEqualTo(spa);
    }

    @Test
    void localDevKeepsLoopbackCallback() {
        String resolved = OAuthRedirectResolver.resolve(
                "github",
                LOCAL,
                "http://localhost:8090",
                LOCAL,
                "http://localhost:5173,http://127.0.0.1:5173"
        );
        assertThat(resolved).isEqualTo(LOCAL);
    }

    @Test
    void publicApiBasePrefersForwardedHeaders() {
        String base = OAuthRedirectResolver.publicApiBase(
                "https",
                "blink-backend-af7x.onrender.com",
                "http",
                "localhost:8090"
        );
        assertThat(base).isEqualTo("https://blink-backend-af7x.onrender.com");
    }
}
