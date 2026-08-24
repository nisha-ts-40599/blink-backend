package com.talentserv.blink.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import com.talentserv.blink.dto.StakeholderRoleResponse;
import com.talentserv.blink.error.ApiException;

@Component
public class RoleCatalog {

    static final String YAML_RESOURCE = "stakeholders.yaml";

    public List<StakeholderRoleResponse> roles() {
        return loadFromYaml();
    }

    public String nameFor(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return "";
        }
        String key = roleCode.trim().toLowerCase(Locale.ROOT);
        return loadFromYaml().stream()
                .filter(role -> key.equals(role.roleCode()))
                .map(StakeholderRoleResponse::roleName)
                .findFirst()
                .orElseGet(() -> displayName(key));
    }

    @SuppressWarnings("unchecked")
    List<StakeholderRoleResponse> loadFromYaml() {
        try (InputStream in = openYaml()) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> root)) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "stakeholders.yaml is empty or invalid.");
            }
            Object blinkNode = root.get("blink");
            Object listNode = blinkNode instanceof Map<?, ?> blink
                    ? blink.get("stakeholders")
                    : root.get("stakeholders");
            if (!(listNode instanceof List<?> rows)) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "stakeholders.yaml has no stakeholders list.");
            }
            List<StakeholderRoleResponse> built = new ArrayList<>();
            int order = 1;
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> values)) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                values.forEach((key, value) -> entry.put(String.valueOf(key), value));
                String roleCode = text(entry.get("role"));
                if (roleCode.isBlank()) {
                    continue;
                }
                roleCode = roleCode.toLowerCase(Locale.ROOT);
                String roleName = displayName(roleCode);
                built.add(new StakeholderRoleResponse(
                        roleCode,
                        roleName,
                        "Default assignee for " + roleName + ".",
                        true,
                        order++,
                        blankToNull(text(entry.get("name"))),
                        blankToNull(text(entry.get("email")))));
            }
            if (built.isEmpty()) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "stakeholders.yaml has no usable roles.");
            }
            return List.copyOf(built);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read stakeholders.yaml.");
        }
    }

    private static InputStream openYaml() throws IOException {
        for (Path candidate : List.of(
                Path.of("src/main/resources", YAML_RESOURCE),
                Path.of("blink-backend/src/main/resources", YAML_RESOURCE))) {
            if (Files.isRegularFile(candidate)) {
                return Files.newInputStream(candidate);
            }
        }
        ClassPathResource resource = new ClassPathResource(YAML_RESOURCE);
        if (!resource.exists()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "stakeholders.yaml was not found on the classpath.");
        }
        return resource.getInputStream();
    }

    static String displayName(String roleCode) {
        String[] parts = roleCode.split("[._-]+");
        StringBuilder label = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(titlePart(part));
        }
        return label.toString();
    }

    private static String titlePart(String part) {
        return switch (part.toLowerCase(Locale.ROOT)) {
            case "dba" -> "DBA";
            case "sre" -> "SRE";
            case "ux" -> "UX";
            case "qa" -> "QA";
            default -> Character.toUpperCase(part.charAt(0)) + part.substring(1).toLowerCase(Locale.ROOT);
        };
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
