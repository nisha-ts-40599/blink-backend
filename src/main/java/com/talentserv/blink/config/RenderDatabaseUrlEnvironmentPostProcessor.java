package com.talentserv.blink.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Maps Render's {@code postgres://} {@code DATABASE_URL} to Spring JDBC properties.
 */
public class RenderDatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (BlinkRuntime.testsRunning()) {
            return;
        }
        boolean onRender = System.getenv("RENDER") != null;
        if (onRender) {
            environment.addActiveProfile("render");
        }
        if (environment.matchesProfiles("nodb")) {
            return;
        }

        String raw = firstNonBlank(
                System.getenv("DATABASE_URL"),
                System.getenv("RENDER_DATABASE_URL"),
                environment.getProperty("DATABASE_URL"),
                environment.getProperty("RENDER_DATABASE_URL"));
        if (raw != null) {
            raw = raw.trim();
        }
        if (isPlaceholder(raw)) {
            raw = null;
        }
        if (raw == null || raw.isBlank()) {
            if (onRender) {
                throw new IllegalStateException(
                        "DATABASE_URL is still the example value (USER/PASSWORD/HOST) or is empty. "
                                + "On the Render Postgres service open Connect and copy Internal Database URL. "
                                + "Paste that full string as DATABASE_URL on this web service.");
            }
            return;
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        if (raw.startsWith("jdbc:postgresql:")) {
            applyJdbc(properties, raw, environment.getProperty("spring.datasource.username"),
                    environment.getProperty("spring.datasource.password"));
        } else if (raw.startsWith("postgres://") || raw.startsWith("postgresql://")) {
            Parsed parsed = parse(raw);
            if (isPlaceholderHost(parsed.jdbcUrl())) {
                throw new IllegalStateException(
                        "DATABASE_URL host is still HOST. Copy Internal Database URL from the Render Postgres service.");
            }
            applyJdbc(properties, parsed.jdbcUrl(), parsed.username(), parsed.password());
            System.out.println("[blink] Using Postgres host=" + hostOf(parsed.jdbcUrl()));
        } else {
            throw new IllegalStateException(
                    "DATABASE_URL must start with postgres://, postgresql://, or jdbc:postgresql://");
        }
        environment.getPropertySources().addFirst(new MapPropertySource("renderDatabaseUrl", properties));
    }

    private static void applyJdbc(Map<String, Object> properties, String jdbcUrl, String username, String password) {
        properties.put("spring.datasource.url", jdbcUrl);
        properties.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        properties.put("spring.jpa.database-platform", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("spring.jpa.properties.jakarta.persistence.jdbc.url", jdbcUrl);
        if (username != null && !username.isBlank()) {
            properties.put("spring.datasource.username", username);
            properties.put("spring.jpa.properties.jakarta.persistence.jdbc.user", username);
        }
        if (password != null) {
            properties.put("spring.datasource.password", password);
            properties.put("spring.jpa.properties.jakarta.persistence.jdbc.password", password);
        }
    }

    static boolean isPlaceholder(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) {
            return true;
        }
        String value = databaseUrl.toLowerCase();
        return value.contains("://user:")
                || value.contains("@host:")
                || value.contains("@host/")
                || value.contains("user:password@host");
    }

    static boolean isPlaceholderHost(String jdbcUrl) {
        String host = hostOf(jdbcUrl);
        return host.equalsIgnoreCase("HOST") || host.startsWith("HOST:");
    }

    static Parsed parse(String databaseUrl) {
        URI uri = URI.create(databaseUrl.replaceFirst("^postgres(ql)?:", "http:"));
        String userInfo = uri.getUserInfo();
        String username = "";
        String password = "";
        if (userInfo != null && !userInfo.isBlank()) {
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                username = decode(userInfo.substring(0, colon));
                password = decode(userInfo.substring(colon + 1));
            } else {
                username = decode(userInfo);
            }
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        int slash = path.indexOf('/');
        if (slash >= 0) {
            path = path.substring(0, slash);
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        boolean renderHost = uri.getHost() != null && uri.getHost().contains("render.com");
        String query = uri.getQuery();
        String ssl;
        if (query != null && !query.isBlank()) {
            ssl = "?" + query;
            if (!query.toLowerCase().contains("sslmode")
                    && (renderHost || System.getenv("RENDER") != null)) {
                ssl += "&sslmode=require";
            }
        } else {
            ssl = renderHost || System.getenv("RENDER") != null ? "?sslmode=require" : "";
        }
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + "/" + path + ssl;
        return new Parsed(jdbcUrl, username, password);
    }

    private static String hostOf(String jdbcUrl) {
        try {
            String withoutPrefix = jdbcUrl.substring("jdbc:postgresql://".length());
            int slash = withoutPrefix.indexOf('/');
            return slash >= 0 ? withoutPrefix.substring(0, slash) : withoutPrefix;
        } catch (RuntimeException ex) {
            return "(unknown)";
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    record Parsed(String jdbcUrl, String username, String password) {
    }
}
