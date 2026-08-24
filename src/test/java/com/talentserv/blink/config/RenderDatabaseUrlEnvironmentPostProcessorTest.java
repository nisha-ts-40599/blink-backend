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

    @Test
    void treatsExampleUrlAsPlaceholder() {
        assertThat(RenderDatabaseUrlEnvironmentPostProcessor.isPlaceholder(
                "postgres://USER:PASSWORD@HOST:5432/DATABASE")).isTrue();
        assertThat(RenderDatabaseUrlEnvironmentPostProcessor.isPlaceholder(
                "postgres://blink:secret@dpg-abc-a/blink_hpt8")).isFalse();
    }

    @Test
    void parsesInternalRenderUrlWithoutRenderDotComHost() {
        RenderDatabaseUrlEnvironmentPostProcessor.Parsed parsed = RenderDatabaseUrlEnvironmentPostProcessor.parse(
                "postgres://blink_user:secret@dpg-example-a:5432/blink_hpt8");
        assertThat(parsed.username()).isEqualTo("blink_user");
        assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://dpg-example-a:5432/blink_hpt8");
    }
}
