package com.talentserv.blink.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DotEnvEnvironmentPostProcessorTest {

    @Test
    void parsesKeyValuesAndIgnoresComments() {
        Map<String, Object> values = DotEnvEnvironmentPostProcessor.parse(List.of(
                "# comment",
                "DATABASE_URL=postgres://user:p@ss@host:5432/blink",
                "export BLINK_AUTOMATION_SDLC_PATH=../automation_sdlc",
                "QUOTED=\"hello world\"",
                "",
                "BADLINE"));
        assertThat(values.get("DATABASE_URL")).isEqualTo("postgres://user:p@ss@host:5432/blink");
        assertThat(values.get("BLINK_AUTOMATION_SDLC_PATH")).isEqualTo("../automation_sdlc");
        assertThat(values.get("QUOTED")).isEqualTo("hello world");
        assertThat(values).doesNotContainKey("BADLINE");
    }
}
