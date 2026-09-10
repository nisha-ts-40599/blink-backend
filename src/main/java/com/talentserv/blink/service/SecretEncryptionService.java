package com.talentserv.blink.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.talentserv.blink.config.BlinkProperties;

/**
 * AES-256-GCM for per-project integration tokens. The key is SHA-256 of
 * {@code BLINK_INTEGRATION_SECRET_KEY}; ciphertext is {@code v1:<base64(iv+cipher+tag)>}.
 */
@Component
public class SecretEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(SecretEncryptionService.class);
    private static final String LOCAL_FALLBACK = "blink-local-dev-do-not-use-in-production";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretEncryptionService(BlinkProperties properties) {
        String secret = properties.getIntegrationSecretKey();
        if (secret == null || secret.isBlank()) {
            secret = LOCAL_FALLBACK;
            log.warn("BLINK_INTEGRATION_SECRET_KEY is not set; using a local development key. Set a unique value before production.");
        }
        this.key = new SecretKeySpec(sha256(secret), "AES");
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] packed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + packed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(packed, 0, out, iv.length, packed.length);
            return "v1:" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not encrypt integration secret.", ex);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String payload = stored.startsWith("v1:") ? stored.substring(3) : stored;
        try {
            byte[] raw = Base64.getDecoder().decode(payload);
            if (raw.length <= IV_BYTES) {
                return null;
            }
            byte[] iv = Arrays.copyOfRange(raw, 0, IV_BYTES);
            byte[] packed = Arrays.copyOfRange(raw, IV_BYTES, raw.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(packed), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.warn("Could not decrypt integration secret: {}", ex.toString());
            return null;
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }
}
