package com.talentserv.blink.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Loads {@code .env} from the working directory so DATABASE_URL does not have to be set in the shell.
 * Existing OS environment variables win over values in the file.
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (BlinkRuntime.testsRunning()) {
            return;
        }
        Path file = resolveEnvFile();
        if (file == null) {
            return;
        }
        Map<String, Object> values = parse(readLines(file));
        if (values.isEmpty()) {
            return;
        }
        Object profiles = values.get("SPRING_PROFILES_ACTIVE");
        if (profiles instanceof String profileValue && !profileValue.isBlank()) {
            values.putIfAbsent("spring.profiles.active", profileValue);
        }
        MapPropertySource source = new MapPropertySource("dotenv", values);
        if (environment.getPropertySources().contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            environment.getPropertySources().addAfter(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    source);
        } else {
            environment.getPropertySources().addLast(source);
        }
    }

    static Path resolveEnvFile() {
        List<Path> candidates = List.of(
                Path.of(System.getProperty("user.dir", "."), ".env"),
                Path.of("blink-backend", ".env"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static Map<String, Object> parse(List<String> lines) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = unquote(line.substring(eq + 1).strip());
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return List.of();
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
