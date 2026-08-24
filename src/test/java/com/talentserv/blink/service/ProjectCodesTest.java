package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ProjectCodesTest {

    @Test
    void fromNameSlugifiesAndAvoidsCollisions() {
        Set<String> existing = new HashSet<>();
        existing.add("BANKING-APPLICATION");
        String code = ProjectCodes.fromName("Banking Application", existing::contains);
        assertThat(code).isEqualTo("BANKING-APPLICATION-2");
        assertThat(ProjectCodes.artifact("Banking Application")).isEqualTo("banking-application");
        assertThat(ProjectCodes.packageName("banking-application")).isEqualTo("com.bankingapplication.backend");
    }
}
