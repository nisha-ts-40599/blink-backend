package com.talentserv.blink.web;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talentserv.blink.dto.AuthLoginConfig;
import com.talentserv.blink.dto.AuthSessionResponse;
import com.talentserv.blink.dto.GmailOAuthUrlResponse;
import com.talentserv.blink.dto.OtpRequest;
import com.talentserv.blink.dto.OtpRequestResponse;
import com.talentserv.blink.dto.OtpVerifyRequest;
import com.talentserv.blink.service.GmailOAuthMailer;
import com.talentserv.blink.service.OAuthRedirectResolver;
import com.talentserv.blink.service.OtpLoginService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final OtpLoginService otpLoginService;
    private final GmailOAuthMailer gmailOAuthMailer;

    public AuthController(OtpLoginService otpLoginService, GmailOAuthMailer gmailOAuthMailer) {
        this.otpLoginService = otpLoginService;
        this.gmailOAuthMailer = gmailOAuthMailer;
    }

    @GetMapping("/config")
    public AuthLoginConfig config() {
        return otpLoginService.loginConfig();
    }

    @PostMapping("/otp/request")
    public OtpRequestResponse requestOtp(@Valid @RequestBody OtpRequest request) {
        return otpLoginService.requestOtp(request.email(), request.accessCode());
    }

    @PostMapping("/otp/verify")
    public AuthSessionResponse verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        return otpLoginService.verifyOtp(request.email(), request.otp(), request.accessCode());
    }

    @GetMapping("/me")
    public AuthSessionResponse me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return otpLoginService.requireSession(authorization);
    }

    @PostMapping("/logout")
    public void logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        otpLoginService.logout(authorization);
    }

    @GetMapping("/gmail/oauth/url")
    public ResponseEntity<?> gmailOAuthUrl(
            @RequestParam(required = false) String format,
            HttpServletRequest request
    ) {
        GmailOAuthUrlResponse info = gmailOAuthMailer.connectInfo(publicApiBase(request));
        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok(info);
        }
        if (info.url() == null || info.url().isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.TEXT_HTML)
                    .body(gmailSetupPage(
                            "Gmail is not ready",
                            info.message() == null
                                    ? "Set BLINK_GMAIL_CLIENT_ID and BLINK_GMAIL_CLIENT_SECRET first."
                                    : info.message(),
                            null,
                            null,
                            info.redirectUri()
                    ));
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(info.url())).build();
    }

    @GetMapping(value = "/gmail/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    public String gmailOAuthCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription,
            HttpServletRequest request
    ) {
        if (error != null && !error.isBlank()) {
            String detail = error + (errorDescription == null || errorDescription.isBlank() ? "" : ": " + errorDescription);
            return gmailSetupPage("Gmail authorization failed", detail, null, null, null);
        }
        GmailOAuthMailer.ConnectResult result = gmailOAuthMailer.exchangeAuthorizationCode(code, publicApiBase(request));
        return gmailSetupPage(
                "Gmail connected",
                "Copy these into the Render (or local) environment, then restart the API. Do not commit them.",
                result.refreshToken(),
                result.email(),
                result.redirectUri()
        );
    }

    private static String publicApiBase(HttpServletRequest request) {
        return OAuthRedirectResolver.publicApiBase(
                request.getHeader("X-Forwarded-Proto"),
                request.getHeader("X-Forwarded-Host"),
                request.getScheme(),
                request.getHeader("Host")
        );
    }

    private static String gmailSetupPage(
            String title,
            String message,
            String refreshToken,
            String email,
            String redirectUri
    ) {
        StringBuilder env = new StringBuilder();
        if (email != null && !email.isBlank()) {
            env.append("BLINK_GMAIL_FROM=").append(email).append('\n');
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            env.append("BLINK_GMAIL_REFRESH_TOKEN=").append(refreshToken).append('\n');
        }
        String envBlock = env.isEmpty()
                ? ""
                : "<pre>" + escape(env.toString()) + "</pre>";
        String redirect = redirectUri == null || redirectUri.isBlank()
                ? ""
                : "<p>Authorized redirect URI: <code>" + escape(redirectUri) + "</code></p>";
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>__TITLE__</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; background: #0f172a; color: #f8fafc; }
                        .card { padding: 2rem; border-radius: 12px; background: #1e293b; border: 1px solid #334155; max-width: 640px; }
                        h2 { margin-top: 0; color: #38bdf8; }
                        pre { white-space: pre-wrap; word-break: break-all; background: #0f172a; padding: 1rem; border-radius: 8px; }
                        code { color: #7dd3fc; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <h2>__TITLE__</h2>
                        <p>__MESSAGE__</p>
                        __ENV__
                        __REDIRECT__
                    </div>
                </body>
                </html>
                """
                .replace("__TITLE__", escape(title))
                .replace("__MESSAGE__", escape(message))
                .replace("__ENV__", envBlock)
                .replace("__REDIRECT__", redirect);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
