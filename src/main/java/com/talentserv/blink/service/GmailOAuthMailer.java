package com.talentserv.blink.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.dto.GmailOAuthUrlResponse;
import com.talentserv.blink.error.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class GmailOAuthMailer {

    private static final Logger log = LoggerFactory.getLogger(GmailOAuthMailer.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final String GMAIL_SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send";
    static final String USERINFO_EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo";
    static final String CALLBACK_PATH = "/api/auth/gmail/oauth/callback";

    private final BlinkProperties properties;
    private final IntegrationHttpGateway http;
    private final Object tokenLock = new Object();
    private String cachedAccessToken;
    private Instant cachedAccessTokenExpiresAt = Instant.EPOCH;

    public GmailOAuthMailer(BlinkProperties properties, IntegrationHttpGateway http) {
        this.properties = properties;
        this.http = http;
    }

    public boolean configured() {
        return properties.gmailConfigured();
    }

    public GmailOAuthUrlResponse connectInfo(String publicApiBase) {
        String redirectUri = resolveRedirectUri(publicApiBase);
        if (!properties.gmailOAuthClientConfigured()) {
            return new GmailOAuthUrlResponse(
                    false,
                    false,
                    null,
                    redirectUri,
                    trimToNull(properties.getGmailFrom()),
                    "Set BLINK_GMAIL_CLIENT_ID and BLINK_GMAIL_CLIENT_SECRET first."
            );
        }
        return new GmailOAuthUrlResponse(
                true,
                properties.gmailConfigured(),
                authorizationUrl(redirectUri),
                redirectUri,
                trimToNull(properties.getGmailFrom()),
                properties.gmailConfigured()
                        ? "This mailbox is already connected. Re-consent only if you need a new refresh token."
                        : "Open the URL, sign in as the sending Gmail account, then copy the refresh token into BLINK_GMAIL_REFRESH_TOKEN."
        );
    }

    public String authorizationUrl(String redirectUri) {
        if (!properties.gmailOAuthClientConfigured()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Set BLINK_GMAIL_CLIENT_ID and BLINK_GMAIL_CLIENT_SECRET first."
            );
        }
        String redirect = required(redirectUri, "Gmail OAuth redirect URI is missing.");
        return AUTHORIZE_URL
                + "?client_id=" + encode(properties.getGmailClientId().trim())
                + "&redirect_uri=" + encode(redirect)
                + "&response_type=code"
                + "&scope=" + encode(GMAIL_SEND_SCOPE + " " + USERINFO_EMAIL_SCOPE)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=true";
    }

    public ConnectResult exchangeAuthorizationCode(String code, String publicApiBase) {
        if (!properties.gmailOAuthClientConfigured()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Set BLINK_GMAIL_CLIENT_ID and BLINK_GMAIL_CLIENT_SECRET first."
            );
        }
        String trimmedCode = required(code, "Google did not return an authorization code.");
        String redirectUri = resolveRedirectUri(publicApiBase);
        String body = "grant_type=authorization_code"
                + "&code=" + encode(trimmedCode)
                + "&client_id=" + encode(properties.getGmailClientId().trim())
                + "&client_secret=" + encode(properties.getGmailClientSecret().trim())
                + "&redirect_uri=" + encode(redirectUri);
        IntegrationHttpGateway.IntegrationHttpResponse res = http.post(
                TOKEN_URL,
                Map.of(),
                body,
                "application/x-www-form-urlencoded"
        );
        if (res.status() < 200 || res.status() >= 300) {
            log.warn("Gmail OAuth code exchange failed: HTTP {}", res.status());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Google did not accept the Gmail authorization.");
        }
        String refreshToken = jsonText(res.body(), "refresh_token");
        String accessToken = jsonText(res.body(), "access_token");
        if (refreshToken.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Google did not return a refresh token. Revoke Blink in Google Account permissions and consent again."
            );
        }
        if (accessToken.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Google did not return an access token.");
        }
        String email = fetchMailboxEmail(accessToken);
        return new ConnectResult(refreshToken, accessToken, email, redirectUri);
    }

    public void send(String to, String subject, String body) {
        if (!configured()) {
            throw new IllegalStateException("Gmail OAuth is not configured.");
        }
        String recipient = required(to, "Recipient is required.");
        String accessToken = accessToken();
        String from = fromAddress(accessToken);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(
                rfc822(from, recipient, subject, body).getBytes(StandardCharsets.UTF_8)
        );
        String json;
        try {
            json = MAPPER.writeValueAsString(Map.of("raw", raw));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not encode the Gmail message.", ex);
        }
        IntegrationHttpGateway.IntegrationHttpResponse res = http.post(
                SEND_URL,
                Map.of("Authorization", "Bearer " + accessToken),
                json,
                "application/json"
        );
        if (res.status() < 200 || res.status() >= 300) {
            log.warn("Gmail send failed: HTTP {} {}", res.status(), googleError(res.body()));
            throw new IllegalStateException(userFacingSendError(res.body()));
        }
    }

    public String resolveRedirectUri(String publicApiBase) {
        String configured = trimToNull(properties.getGmailRedirectUri());
        if (configured != null) {
            return configured;
        }
        String root = trimToNull(publicApiBase);
        if (root == null) {
            return "http://localhost:8090" + CALLBACK_PATH;
        }
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.toLowerCase(Locale.ROOT).endsWith("/api")) {
            root = root.substring(0, root.length() - 4);
        }
        return root + CALLBACK_PATH;
    }

    private String accessToken() {
        synchronized (tokenLock) {
            if (cachedAccessToken != null && Instant.now().plusSeconds(60).isBefore(cachedAccessTokenExpiresAt)) {
                return cachedAccessToken;
            }
            String body = "grant_type=refresh_token"
                    + "&refresh_token=" + encode(properties.getGmailRefreshToken().trim())
                    + "&client_id=" + encode(properties.getGmailClientId().trim())
                    + "&client_secret=" + encode(properties.getGmailClientSecret().trim());
            IntegrationHttpGateway.IntegrationHttpResponse res = http.post(
                    TOKEN_URL,
                    Map.of(),
                    body,
                    "application/x-www-form-urlencoded"
            );
            if (res.status() < 200 || res.status() >= 300) {
                log.warn("Gmail token refresh failed: HTTP {}", res.status());
                throw new IllegalStateException("Could not refresh the Gmail access token.");
            }
            String token = jsonText(res.body(), "access_token");
            if (token.isBlank()) {
                throw new IllegalStateException("Google did not return a Gmail access token.");
            }
            int expiresIn = jsonInt(res.body(), "expires_in", 3500);
            cachedAccessToken = token;
            cachedAccessTokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn));
            return token;
        }
    }

    private String fromAddress(String accessToken) {
        String configured = trimToNull(properties.getGmailFrom());
        if (configured != null) {
            return configured;
        }
        String email = fetchMailboxEmail(accessToken);
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("Set BLINK_GMAIL_FROM to the connected Gmail address.");
        }
        return email;
    }

    private String fetchMailboxEmail(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        try {
            IntegrationHttpGateway.IntegrationHttpResponse res = http.get(
                    USERINFO_URL,
                    Map.of("Authorization", "Bearer " + accessToken)
            );
            if (res.status() >= 200 && res.status() < 300) {
                return trimToNull(jsonText(res.body(), "email"));
            }
        } catch (RuntimeException ex) {
            log.warn("Could not read Gmail userinfo: {}", ex.toString());
        }
        return null;
    }

    static String rfc822(String from, String to, String subject, String body) {
        String fromHeader = from.contains("<") ? from : "Blink <" + from + ">";
        String text = body == null ? "" : body.replace("\r\n", "\n").replace("\n", "\r\n");
        return "From: " + fromHeader + "\r\n"
                + "To: " + to + "\r\n"
                + "Subject: " + (subject == null ? "" : subject) + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + text;
    }

    private static String googleError(String body) {
        JsonNode node = parse(body);
        if (node == null) {
            return "";
        }
        JsonNode error = node.get("error");
        if (error == null || error.isNull()) {
            return "";
        }
        if (error.isTextual()) {
            return error.asText("");
        }
        String message = error.path("message").asText("");
        String status = error.path("status").asText("");
        if (message.isBlank()) {
            return status;
        }
        return status.isBlank() ? message : status + ": " + message;
    }

    private static String userFacingSendError(String body) {
        String detail = googleError(body);
        if (detail.toLowerCase(Locale.ROOT).contains("has not been used")
                || detail.toLowerCase(Locale.ROOT).contains("disabled")
                || detail.toLowerCase(Locale.ROOT).contains("accessnotconfigured")) {
            return "Enable the Gmail API in Google Cloud, wait a minute, then try again.";
        }
        return "Gmail did not accept the message.";
    }

    private static String jsonText(String body, String field) {
        JsonNode node = parse(body);
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !(value.isTextual() || value.isNumber())) {
            return "";
        }
        String text = value.asText("");
        return text == null ? "" : text.trim();
    }

    private static int jsonInt(String body, String field, int fallback) {
        JsonNode node = parse(body);
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return fallback;
        }
        int parsed = value.asInt(fallback);
        return parsed > 0 ? parsed : fallback;
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String required(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ConnectResult(String refreshToken, String accessToken, String email, String redirectUri) {
    }
}
