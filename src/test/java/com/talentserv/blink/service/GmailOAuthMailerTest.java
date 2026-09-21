package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.dto.GmailOAuthUrlResponse;
import com.talentserv.blink.error.ApiException;

class GmailOAuthMailerTest {

    private BlinkProperties properties;
    private RecordingHttp http;
    private GmailOAuthMailer mailer;

    @BeforeEach
    void setUp() {
        properties = new BlinkProperties();
        properties.setGmailClientId("client-id");
        properties.setGmailClientSecret("client-secret");
        properties.setGmailFrom("blink.mailer@gmail.com");
        http = new RecordingHttp();
        mailer = new GmailOAuthMailer(properties, http);
    }

    @Test
    void connectInfoRequiresClientCredentials() {
        properties.setGmailClientId("");
        GmailOAuthUrlResponse info = mailer.connectInfo("https://blink-backend-af7x.onrender.com");
        assertThat(info.clientConfigured()).isFalse();
        assertThat(info.url()).isNull();
        assertThat(info.redirectUri()).isEqualTo(
                "https://blink-backend-af7x.onrender.com/api/auth/gmail/oauth/callback"
        );
    }

    @Test
    void authorizationUrlAsksForOfflineGmailSend() {
        String url = mailer.authorizationUrl("http://localhost:8090/api/auth/gmail/oauth/callback");
        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(url).contains("client_id=client-id");
        assertThat(url).contains("access_type=offline");
        assertThat(url).contains("prompt=consent");
        assertThat(url).contains("gmail.send");
    }

    @Test
    void exchangeAuthorizationCodeReturnsRefreshTokenAndMailbox() {
        http.responses.add(new IntegrationHttpGateway.IntegrationHttpResponse(
                200,
                "{\"refresh_token\":\"1//refresh\",\"access_token\":\"ya29.access\"}"
        ));
        http.responses.add(new IntegrationHttpGateway.IntegrationHttpResponse(
                200,
                "{\"email\":\"blink.mailer@gmail.com\"}"
        ));

        GmailOAuthMailer.ConnectResult result = mailer.exchangeAuthorizationCode(
                "auth-code",
                "http://localhost:8090"
        );

        assertThat(result.refreshToken()).isEqualTo("1//refresh");
        assertThat(result.email()).isEqualTo("blink.mailer@gmail.com");
        assertThat(http.urls.get(0)).isEqualTo(GmailOAuthMailer.TOKEN_URL);
        assertThat(http.bodies.get(0)).contains("grant_type=authorization_code");
        assertThat(http.bodies.get(0)).contains("code=auth-code");
        assertThat(http.contentTypes.get(0)).isEqualTo("application/x-www-form-urlencoded");
    }

    @Test
    void sendRefreshesAccessTokenThenPostsRfc822ToGmailApi() {
        properties.setGmailRefreshToken("1//refresh");
        http.responses.add(new IntegrationHttpGateway.IntegrationHttpResponse(
                200,
                "{\"access_token\":\"ya29.access\",\"expires_in\":3600}"
        ));
        http.responses.add(new IntegrationHttpGateway.IntegrationHttpResponse(200, "{\"id\":\"msg-1\"}"));

        mailer.send("ada@talentserv.co.in", "Your Blink sign-in code", "123456");

        assertThat(http.urls.get(0)).isEqualTo(GmailOAuthMailer.TOKEN_URL);
        assertThat(http.bodies.get(0)).contains("grant_type=refresh_token");
        assertThat(http.urls.get(1)).isEqualTo(GmailOAuthMailer.SEND_URL);
        assertThat(http.headers.get(1)).containsEntry("Authorization", "Bearer ya29.access");
        assertThat(http.bodies.get(1)).contains("\"raw\":");
        String mime = GmailOAuthMailer.rfc822(
                "blink.mailer@gmail.com",
                "ada@talentserv.co.in",
                "Your Blink sign-in code",
                "123456"
        );
        assertThat(mime).contains("To: ada@talentserv.co.in");
        assertThat(mime).contains("From: Blink <blink.mailer@gmail.com>");
        assertThat(mime).contains("123456");
    }

    @Test
    void sendFailsWhenGmailApiRejects() {
        properties.setGmailRefreshToken("1//refresh");
        http.responses.add(new IntegrationHttpGateway.IntegrationHttpResponse(
                200,
                "{\"access_token\":\"ya29.access\",\"expires_in\":3600}"
        ));
        http.responses.add(new IntegrationHttpGateway.IntegrationHttpResponse(
                403,
                "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"Gmail API has not been used in project 1 before or it is disabled.\"}}"
        ));
        assertThatThrownBy(() -> mailer.send("ada@talentserv.co.in", "Code", "000000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Enable the Gmail API");
    }

    @Test
    void exchangeFailsWithoutRefreshToken() {
        http.responses.add(new IntegrationHttpGateway.IntegrationHttpResponse(
                200,
                "{\"access_token\":\"ya29.access\"}"
        ));
        assertThatThrownBy(() -> mailer.exchangeAuthorizationCode("auth-code", "http://localhost:8090"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("refresh token");
    }

    private static final class RecordingHttp implements IntegrationHttpGateway {
        private final List<String> urls = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private final List<String> contentTypes = new ArrayList<>();
        private final List<Map<String, String>> headers = new ArrayList<>();
        private final List<IntegrationHttpResponse> responses = new ArrayList<>();

        @Override
        public IntegrationHttpResponse get(String url, Map<String, String> headers) {
            urls.add(url);
            bodies.add(null);
            contentTypes.add(null);
            this.headers.add(headers);
            return next();
        }

        @Override
        public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
            return post(url, headers, jsonBody, "application/json");
        }

        @Override
        public IntegrationHttpResponse post(String url, Map<String, String> headers, String body, String contentType) {
            urls.add(url);
            bodies.add(body);
            contentTypes.add(contentType);
            this.headers.add(headers);
            return next();
        }

        private IntegrationHttpResponse next() {
            if (responses.isEmpty()) {
                return new IntegrationHttpResponse(500, "");
            }
            return responses.remove(0);
        }
    }
}
