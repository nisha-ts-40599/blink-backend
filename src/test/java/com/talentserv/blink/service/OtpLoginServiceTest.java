package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.dto.AuthSessionResponse;
import com.talentserv.blink.dto.OtpRequestResponse;
import com.talentserv.blink.error.ApiException;

class OtpLoginServiceTest {

    private static final Pattern OTP = Pattern.compile("(?m)^(\\d{6})$");

    private BlinkProperties properties;
    private MutableClock clock;
    private CapturingMail mail;
    private OtpLoginService service;

    @BeforeEach
    void setUp() {
        properties = new BlinkProperties();
        properties.setSmtpHost("smtp.example.com");
        properties.setLoginAllowedDomain("talentserv.co.in");
        properties.setOtpTtl(Duration.ofMinutes(5));
        properties.setOtpResendCooldown(Duration.ofSeconds(45));
        properties.setSessionTtl(Duration.ofHours(12));
        properties.setOtpMaxAttempts(5);
        clock = new MutableClock(Instant.parse("2026-09-11T00:00:00Z"));
        mail = new CapturingMail(properties);
        service = new OtpLoginService(properties, mail, clock);
    }

    @Test
    void rejectsNonTalentservDomain() {
        assertThatThrownBy(() -> service.requestOtp("ada@gmail.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("talentserv.co.in");
        assertThatThrownBy(() -> service.requestOtp("ada@talentserv.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("talentserv.co.in");
        assertThatThrownBy(() -> service.requestOtp("ada@mail.talentserv.co.in"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void refusesWhenSmtpIsNotConfigured() {
        properties.setSmtpHost("");
        OtpLoginService noMail = new OtpLoginService(properties, new StakeholderEmailService(properties), clock);
        assertThatThrownBy(() -> noMail.requestOtp("ada@talentserv.co.in"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void localRevealReturnsOtpWithoutSendingMail() {
        properties.setOtpReveal(true);
        properties.setLoginGate("demo-gate");
        OtpRequestResponse requested = service.requestOtp("ada@talentserv.co.in", "demo-gate");
        assertThat(requested.deliveryMode()).isEqualTo("local");
        assertThat(requested.otp()).isNotBlank().hasSize(6);
        assertThat(mail.lastBody).isEmpty();
        AuthSessionResponse session = service.verifyOtp("ada@talentserv.co.in", requested.otp(), "demo-gate");
        assertThat(session.token()).isNotBlank();
    }

    @Test
    void revealRequiresAccessCode() {
        properties.setOtpReveal(true);
        properties.setLoginGate("demo-gate");
        assertThatThrownBy(() -> service.requestOtp("ada@talentserv.co.in", "wrong"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Access code");
        assertThatThrownBy(() -> service.requestOtp("ada@gmail.com", "demo-gate"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("talentserv.co.in");
    }

    @Test
    void sendsOtpByEmailAndDoesNotReturnTheCode() {
        OtpRequestResponse requested = service.requestOtp("Ada.Lee@TalentServ.co.in");
        assertThat(requested.deliveryMode()).isEqualTo("smtp");
        assertThat(requested.message()).contains("ada.lee@talentserv.co.in");
        assertThat(requested.otp()).isNull();
        String otp = readOtp(mail.lastBody);
        AuthSessionResponse session = service.verifyOtp("ada.lee@talentserv.co.in", otp);
        assertThat(session.email()).isEqualTo("ada.lee@talentserv.co.in");
        assertThat(session.token()).isNotBlank();
        assertThat(service.requireSession("Bearer " + session.token()).email())
                .isEqualTo("ada.lee@talentserv.co.in");
    }

    @Test
    void wrongOtpIsRejected() {
        service.requestOtp("ada@talentserv.co.in");
        assertThat(readOtp(mail.lastBody)).isNotEqualTo("000000");
        assertThatThrownBy(() -> service.verifyOtp("ada@talentserv.co.in", "000000"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("incorrect");
    }

    @Test
    void expiredOtpIsRejected() {
        service.requestOtp("ada@talentserv.co.in");
        String otp = readOtp(mail.lastBody);
        clock.advance(Duration.ofMinutes(6));
        assertThatThrownBy(() -> service.verifyOtp("ada@talentserv.co.in", otp))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("incorrect");
    }

    private static String readOtp(String body) {
        Matcher matcher = OTP.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static final class CapturingMail extends StakeholderEmailService {
        private String lastBody = "";

        private CapturingMail(BlinkProperties properties) {
            super(properties);
        }

        @Override
        public TextMailResult sendText(String to, String subject, String body) {
            lastBody = body == null ? "" : body;
            return new TextMailResult("smtp", null);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
