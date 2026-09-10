package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.talentserv.blink.config.BlinkProperties;

class SecretEncryptionServiceTest {

    @Test
    void roundTripsPlaintext() {
        BlinkProperties properties = new BlinkProperties();
        properties.setIntegrationSecretKey("test-integration-secret");
        SecretEncryptionService encryption = new SecretEncryptionService(properties);
        String cipher = encryption.encrypt("ghp_super_secret");
        assertThat(cipher).startsWith("v1:");
        assertThat(cipher).doesNotContain("ghp_super_secret");
        assertThat(encryption.decrypt(cipher)).isEqualTo("ghp_super_secret");
    }

    @Test
    void differentIvEachEncrypt() {
        BlinkProperties properties = new BlinkProperties();
        properties.setIntegrationSecretKey("test-integration-secret");
        SecretEncryptionService encryption = new SecretEncryptionService(properties);
        assertThat(encryption.encrypt("same")).isNotEqualTo(encryption.encrypt("same"));
    }
}
