package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FrameworkKitFilterTest {

    @Test
    void keepsFrameworkKitAndDropsWorkerTestsAndLocalPython() {
        assertThat(FrameworkKitFilter.skipDirectory("ai-sdlc")).isFalse();
        assertThat(FrameworkKitFilter.skipDirectory("prompts")).isFalse();
        assertThat(FrameworkKitFilter.skipDirectory("tools")).isFalse();
        assertThat(FrameworkKitFilter.skipDirectory("schemas")).isFalse();

        assertThat(FrameworkKitFilter.skipDirectory("services")).isTrue();
        assertThat(FrameworkKitFilter.skipDirectory("docs")).isTrue();
        assertThat(FrameworkKitFilter.skipDirectory(".github")).isTrue();
        assertThat(FrameworkKitFilter.skipDirectory("tests")).isTrue();
        assertThat(FrameworkKitFilter.skipDirectory(".tools")).isTrue();
        assertThat(FrameworkKitFilter.skipDirectory("test_greenfield_setup")).isTrue();

        assertThat(FrameworkKitFilter.skipFile("Makefile")).isFalse();
        assertThat(FrameworkKitFilter.skipFile("validate_setup_readiness.py")).isFalse();
        assertThat(FrameworkKitFilter.skipFile("test_validate_setup_readiness.py")).isTrue();
        assertThat(FrameworkKitFilter.skipFile("path_security_test.py")).isTrue();
        assertThat(FrameworkKitFilter.skipFile("conftest.py")).isTrue();
    }
}
