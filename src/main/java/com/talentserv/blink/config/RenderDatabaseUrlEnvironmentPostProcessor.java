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
        String raw = firstNonBlank(
                environment.getProperty("DATABASE_URL"),
                environment.getProperty("RENDER_DATABASE_URL"));
        if (raw == null || raw.isBlank()) {
            return;
        }
        if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) {
            return;
        }

        Parsed parsed = parse(raw);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", parsed.jdbcUrl());
        properties.put("spring.datasource.username", parsed.username());
        properties.put("spring.datasource.password", parsed.password());
        environment.getPropertySources().addFirst(new MapPropertySource("renderDatabaseUrl", properties));
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
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        boolean renderHost = uri.getHost() != null && uri.getHost().contains("render.com");
        String ssl = renderHost || System.getenv("RENDER") != null ? "?sslmode=require" : "";
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + "/" + path + ssl;
        return new Parsed(jdbcUrl, username, password);
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
