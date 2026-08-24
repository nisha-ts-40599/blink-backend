package com.talentserv.blink.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RenderDatabaseUrlEnvironmentPostProcessorTest {

    @Test
    void parsesRenderStylePostgresUrl() {
        RenderDatabaseUrlEnvironmentPostProcessor.Parsed parsed = RenderDatabaseUrlEnvironmentPostProcessor.parse(
                "postgres://blink_user:p%40ss@dpg-example-a.oregon-postgres.render.com:5432/blink");
        assertThat(parsed.username()).isEqualTo("blink_user");
        assertThat(parsed.password()).isEqualTo("p@ss");
        assertThat(parsed.jdbcUrl()).startsWith("jdbc:postgresql://dpg-example-a.oregon-postgres.render.com:5432/blink");
        assertThat(parsed.jdbcUrl()).contains("sslmode=require");
    }
}
