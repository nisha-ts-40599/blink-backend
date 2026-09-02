package com.talentserv.blink.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import com.zaxxer.hikari.HikariDataSource;

@Configuration
@Profile("!nodb")
public class DatabaseUrlDataSourceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUrlDataSourceConfiguration.class);

    @Bean
    @Primary
    DataSource dataSource(Environment environment) {
        String configured = environment.getProperty("spring.datasource.url");
        if (BlinkRuntime.testsRunning() || (configured != null && configured.startsWith("jdbc:h2:"))) {
            return DataSourceBuilder.create()
                    .type(HikariDataSource.class)
                    .url(configured)
                    .username(environment.getProperty("spring.datasource.username", "sa"))
                    .password(environment.getProperty("spring.datasource.password", ""))
                    .driverClassName("org.h2.Driver")
                    .build();
        }

        String databaseUrl = firstNonBlank(
                System.getenv("DATABASE_URL"),
                System.getenv("RENDER_DATABASE_URL"),
                environment.getProperty("DATABASE_URL"),
                fromDotEnv("DATABASE_URL"));
        boolean onRender = System.getenv("RENDER") != null;

        if (RenderDatabaseUrlEnvironmentPostProcessor.isPlaceholder(databaseUrl)) {
            databaseUrl = null;
        }

        if (databaseUrl != null && (databaseUrl.startsWith("postgres://") || databaseUrl.startsWith("postgresql://"))) {
            RenderDatabaseUrlEnvironmentPostProcessor.Parsed parsed =
                    RenderDatabaseUrlEnvironmentPostProcessor.parse(databaseUrl);
            log.info("Connecting to Postgres at {}", host(parsed.jdbcUrl()));
            return postgres(parsed.jdbcUrl(), parsed.username(), parsed.password());
        }

        if (databaseUrl != null && databaseUrl.startsWith("jdbc:postgresql:")) {
            log.info("Connecting to Postgres at {}", host(databaseUrl));
            return postgres(
                    databaseUrl,
                    environment.getProperty("spring.datasource.username"),
                    environment.getProperty("spring.datasource.password"));
        }

        if (onRender) {
            throw new IllegalStateException(
                    "DATABASE_URL is missing or still the example postgres://USER:PASSWORD@HOST:5432/DATABASE. "
                            + "On the Render Postgres service, Connect → copy Internal Database URL, "
                            + "then paste it as DATABASE_URL on this web service.");
        }

        return postgres(
                configured,
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"));
    }

    @Bean
    HibernatePropertiesCustomizer hibernateDialectCustomizer(DataSource dataSource) {
        String jdbcUrl = jdbcUrlOf(dataSource);
        boolean h2 = jdbcUrl != null && jdbcUrl.contains(":h2:");
        return properties -> {
            properties.put(
                    "hibernate.dialect",
                    h2 ? "org.hibernate.dialect.H2Dialect" : "org.hibernate.dialect.PostgreSQLDialect");
            properties.put("hibernate.boot.allow_jdbc_metadata_access", h2 ? "true" : "false");
        };
    }

    private static DataSource postgres(String jdbcUrl, String username, String password) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(jdbcUrl)
                .username(username)
                .password(password)
                .driverClassName("org.postgresql.Driver")
                .build();
    }

    private static String jdbcUrlOf(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getJdbcUrl();
        }
        return null;
    }

    private static String fromDotEnv(String key) {
        var file = DotEnvEnvironmentPostProcessor.resolveEnvFile();
        if (file == null || BlinkRuntime.testsRunning()) {
            return null;
        }
        try {
            Object value = DotEnvEnvironmentPostProcessor.parse(java.nio.file.Files.readAllLines(file)).get(key);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String host(String jdbcUrl) {
        String withoutPrefix = jdbcUrl.replace("jdbc:postgresql://", "");
        int slash = withoutPrefix.indexOf('/');
        return slash >= 0 ? withoutPrefix.substring(0, slash) : withoutPrefix;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
