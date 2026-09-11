package com.talentserv.blink.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.dto.AuthLoginConfig;
import com.talentserv.blink.dto.AuthSessionResponse;
import com.talentserv.blink.dto.OtpRequestResponse;
import com.talentserv.blink.error.ApiException;

@Service
public class OtpLoginService {

    private static final Logger log = LoggerFactory.getLogger(OtpLoginService.class);
    private static final String INVALID_CODE = "That code is incorrect or has expired.";

    private final BlinkProperties properties;
    private final StakeholderEmailService emailService;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, OtpChallenge> challenges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionRecord> sessions = new ConcurrentHashMap<>();

    @Autowired
    public OtpLoginService(BlinkProperties properties, StakeholderEmailService emailService) {
        this(properties, emailService, Clock.systemUTC());
    }

    OtpLoginService(BlinkProperties properties, StakeholderEmailService emailService, Clock clock) {
        this.properties = properties;
        this.emailService = emailService;
        this.clock = clock;
    }

    public AuthLoginConfig loginConfig() {
        return new AuthLoginConfig(allowedDomain(), properties.loginGateRequired(), properties.isOtpReveal());
    }

    public OtpRequestResponse requestOtp(String rawEmail) {
        return requestOtp(rawEmail, null);
    }

    public OtpRequestResponse requestOtp(String rawEmail, String accessCode) {
        String email = normalizeEmail(rawEmail);
        if (!isAllowedEmail(email)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Use your TalentServ email (@" + allowedDomain() + ")."
            );
        }
        requireGate(accessCode);
        Instant now = clock.instant();
        OtpChallenge existing = challenges.get(email);
        Duration cooldown = safeDuration(properties.getOtpResendCooldown(), Duration.ofSeconds(45));
        if (existing != null && existing.lastSentAt().plus(cooldown).isAfter(now)) {
            long wait = Duration.between(now, existing.lastSentAt().plus(cooldown)).toSeconds();
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Wait " + Math.max(1, wait) + " seconds before requesting another code."
            );
        }

        String otp = String.format("%06d", random.nextInt(1_000_000));
        Duration ttl = safeDuration(properties.getOtpTtl(), Duration.ofMinutes(5));
        challenges.put(email, new OtpChallenge(sha256(otp), now.plus(ttl), now, 0));

        if (properties.isOtpReveal()) {
            log.info("Demo OTP reveal is on for {}", email);
            return new OtpRequestResponse(
                    "Demo OTP is on. Use this code. Set BLINK_OTP_REVEAL=false when SMTP is ready.",
                    "local",
                    (int) ttl.toSeconds(),
                    (int) cooldown.toSeconds(),
                    otp
            );
        }

        String subject = "Your Blink sign-in code";
        String body = """
                Hi,

                Your Blink one-time password is:

                %s

                This code expires in %s minutes. If you did not request it, you can ignore this email.

                — Blink
                """.formatted(otp, Math.max(1, ttl.toMinutes()));
        StakeholderEmailService.TextMailResult delivery;
        try {
            delivery = emailService.sendText(email, subject, body);
        } catch (RuntimeException ex) {
            challenges.remove(email);
            log.warn("OTP email failed for {}: {}", email, ex.toString());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not send the sign-in code. Check SMTP settings and try again."
            );
        }
        if (!"smtp".equals(delivery.deliveryMode())) {
            challenges.remove(email);
            log.warn("OTP email skipped for {}; SMTP is not configured", email);
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Sign-in email is not configured on this server."
            );
        }
        log.info("Sent sign-in OTP to {} via smtp", email);
        return new OtpRequestResponse(
                "We sent a one-time password to " + email + ".",
                "smtp",
                (int) ttl.toSeconds(),
                (int) cooldown.toSeconds(),
                null
        );
    }

    public AuthSessionResponse verifyOtp(String rawEmail, String rawOtp) {
        return verifyOtp(rawEmail, rawOtp, null);
    }

    public AuthSessionResponse verifyOtp(String rawEmail, String rawOtp, String accessCode) {
        String email = normalizeEmail(rawEmail);
        if (!isAllowedEmail(email)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Use your TalentServ email (@" + allowedDomain() + ")."
            );
        }
        requireGate(accessCode);
        String otp = rawOtp == null ? "" : rawOtp.trim().replace(" ", "");
        if (!otp.matches("\\d{6}")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CODE);
        }

        Instant now = clock.instant();
        OtpChallenge challenge = challenges.get(email);
        if (challenge == null || !challenge.expiresAt().isAfter(now)) {
            challenges.remove(email);
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CODE);
        }
        int attempts = challenge.attempts() + 1;
        int maxAttempts = properties.getOtpMaxAttempts() <= 0 ? 5 : properties.getOtpMaxAttempts();
        if (attempts > maxAttempts) {
            challenges.remove(email);
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CODE);
        }
        if (!MessageDigest.isEqual(
                challenge.codeHash().getBytes(StandardCharsets.UTF_8),
                sha256(otp).getBytes(StandardCharsets.UTF_8)
        )) {
            challenges.put(email, new OtpChallenge(
                    challenge.codeHash(),
                    challenge.expiresAt(),
                    challenge.lastSentAt(),
                    attempts
            ));
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CODE);
        }

        challenges.remove(email);
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Duration sessionTtl = safeDuration(properties.getSessionTtl(), Duration.ofHours(12));
        Instant expiresAt = now.plus(sessionTtl);
        sessions.put(sha256(token), new SessionRecord(email, expiresAt));
        return new AuthSessionResponse(token, email, expiresAt.toString());
    }

    public AuthSessionResponse requireSession(String authorizationHeader) {
        SessionRecord session = resolve(authorizationHeader);
        if (session == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to continue.");
        }
        return new AuthSessionResponse(null, session.email(), session.expiresAt().toString());
    }

    public String optionalEmail(String authorizationHeader) {
        SessionRecord session = resolve(authorizationHeader);
        return session == null ? null : session.email();
    }

    public void logout(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        if (token != null) {
            sessions.remove(sha256(token));
        }
    }

    static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    boolean isAllowedEmail(String email) {
        String domain = allowedDomain();
        int at = email.lastIndexOf('@');
        if (at <= 0 || at != email.indexOf('@') || at == email.length() - 1) {
            return false;
        }
        String local = email.substring(0, at);
        String host = email.substring(at + 1);
        return !local.isBlank()
                && !local.contains(" ")
                && host.equals(domain);
    }

    private void requireGate(String provided) {
        if (properties.isOtpReveal() && blank(properties.getLoginGate())) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Set BLINK_LOGIN_GATE while OTP reveal is on."
            );
        }
        String expected = properties.getLoginGate();
        if (expected == null || expected.isBlank()) {
            return;
        }
        String got = provided == null ? "" : provided;
        if (!MessageDigest.isEqual(
                sha256(expected).getBytes(StandardCharsets.UTF_8),
                sha256(got).getBytes(StandardCharsets.UTF_8)
        )) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Access code is incorrect.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String allowedDomain() {
        String domain = properties.getLoginAllowedDomain();
        if (domain == null || domain.isBlank()) {
            return "talentserv.co.in";
        }
        return domain.trim().toLowerCase(Locale.ROOT);
    }

    private SessionRecord resolve(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        if (token == null) {
            return null;
        }
        SessionRecord session = sessions.get(sha256(token));
        if (session == null) {
            return null;
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(sha256(token));
            return null;
        }
        return session;
    }

    private static String bearerToken(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (value.length() < 8 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = value.substring(7).trim();
        return token.isBlank() ? null : token;
    }

    private static Duration safeDuration(Duration value, Duration fallback) {
        if (value == null || value.isZero() || value.isNegative()) {
            return fallback;
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is required for OTP login.", ex);
        }
    }

    private record OtpChallenge(String codeHash, Instant expiresAt, Instant lastSentAt, int attempts) {
    }

    private record SessionRecord(String email, Instant expiresAt) {
    }
}
