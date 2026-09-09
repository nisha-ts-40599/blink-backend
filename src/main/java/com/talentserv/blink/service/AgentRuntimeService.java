package com.talentserv.blink.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.dto.GroomAnswerRequest;
import com.talentserv.blink.dto.GroomClarifyRequest;
import com.talentserv.blink.dto.StakeholderRequest;
import com.talentserv.blink.error.ApiException;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AgentRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BlinkProperties properties;
    private final CloseableHttpClient client = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(3))
                            .setSocketTimeout(Timeout.ofSeconds(90))
                            .build())
                    .build())
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setResponseTimeout(Timeout.ofSeconds(90))
                    .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                    .build())
            .build();

    public AgentRuntimeService(BlinkProperties properties) {
        this.properties = properties;
    }

    @PreDestroy
    void closeClient() {
        try {
            client.close();
        } catch (IOException ex) {
            log.debug("Agent HTTP client close: {}", ex.toString());
        }
    }

    public JsonNode invokeClarify(GroomClarifyRequest request) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("command", "clarify-requirement");
        payload.put("mode", "discovery");
        payload.put("requirementText", request.requirementText().trim());
        if (request.projectName() != null && !request.projectName().isBlank()) {
            payload.put("projectName", request.projectName().trim());
        }
        if (request.projectId() != null && !request.projectId().isBlank()) {
            payload.put("projectId", request.projectId().trim());
        }
        if (request.answers() != null) {
            var answers = payload.putArray("answers");
            for (GroomAnswerRequest answer : request.answers()) {
                if (answer == null || answer.questionId() == null || answer.optionId() == null) {
                    continue;
                }
                if ("other".equalsIgnoreCase(answer.optionId().trim())
                        && (answer.otherText() == null || answer.otherText().isBlank())) {
                    continue;
                }
                ObjectNode row = answers.addObject();
                row.put("questionId", answer.questionId().trim());
                row.put("optionId", answer.optionId().trim());
                if (answer.optionLabel() != null && !answer.optionLabel().isBlank()) {
                    row.put("optionLabel", answer.optionLabel().trim());
                }
                if (answer.otherText() != null && !answer.otherText().isBlank()) {
                    row.put("otherText", answer.otherText().trim());
                }
            }
        }
        return invoke(payload);
    }

    public JsonNode invokeSetupApply(String projectName, String requirementText, String projectDescription) {
        return invokeSetupApply(projectName, requirementText, projectDescription, null);
    }

    public JsonNode invokeSetupApply(
            String projectName,
            String requirementText,
            String projectDescription,
            Timeout responseTimeout
    ) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("command", "setup-new-workspace");
        payload.put("mode", "apply");
        payload.put("projectName", projectName == null ? "" : projectName);
        if (requirementText != null && !requirementText.isBlank()) {
            payload.put("requirementText", requirementText.trim());
        }
        if (projectDescription != null && !projectDescription.isBlank()) {
            payload.put("projectDescription", projectDescription.trim());
        }
        return invoke(payload, responseTimeout);
    }

    public JsonNode invokeConfigureStakeholders(
            String projectName,
            String projectId,
            List<StakeholderRequest> stakeholders,
            String mode
    ) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("command", "configure-stakeholders");
        payload.put("mode", mode == null || mode.isBlank() ? "apply" : mode);
        payload.put("projectName", projectName == null ? "" : projectName);
        if (projectId != null && !projectId.isBlank()) {
            payload.put("projectId", projectId);
        }
        if (stakeholders != null && !stakeholders.isEmpty()) {
            ArrayNode rows = payload.putArray("stakeholders");
            for (StakeholderRequest s : stakeholders) {
                if (s == null) continue;
                ObjectNode row = rows.addObject();
                row.put("role_id", s.roleCode() == null ? "" : s.roleCode().trim());
                row.put("name", s.name() == null ? "" : s.name().trim());
                row.put("email", s.email() == null ? "" : s.email().trim());
            }
        }
        return invoke(payload);
    }

    public JsonNode invokePlanProductScope(
            String projectName,
            String projectId,
            String requirementText,
            String actor
    ) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("command", "plan-product-scope");
        payload.put("projectName", projectName == null ? "" : projectName);
        if (projectId != null && !projectId.isBlank()) {
            payload.put("projectId", projectId);
        }
        if (requirementText != null && !requirementText.isBlank()) {
            payload.put("requirementText", requirementText.trim());
        }
        if (actor != null && !actor.isBlank()) {
            payload.put("actor", actor.trim());
        }
        return invoke(payload);
    }

    public JsonNode invoke(JsonNode body) {
        return invoke(body, null);
    }

    public JsonNode invoke(JsonNode body, Timeout responseTimeout) {
        String url = properties.getAgentRuntimeUrl();
        if (url == null || url.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Agent runtime URL is not configured.");
        }
        String token = properties.getAgentRuntimeToken();
        if (token == null || token.isBlank()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent runtime token is not configured. Set BLINK_AGENT_RUNTIME_TOKEN."
            );
        }

        ObjectNode payload = copyObject(body);
        if (!payload.hasNonNull("command") || payload.get("command").asText().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Specify command as clarify-requirement or setup-new-workspace.");
        }

        HttpPost post = new HttpPost(url.trim());
        post.setHeader("Accept", "application/json");
        post.setHeader("User-Agent", "Blink-Backend/1.0");
        post.setHeader("Authorization", "Bearer " + token.trim());
        post.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));
        if (responseTimeout != null) {
            post.setConfig(RequestConfig.custom()
                    .setResponseTimeout(responseTimeout)
                    .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                    .build());
        }

        log.info(
                "Calling agent runtime command={} timeoutSec={}",
                payload.path("command").asText(""),
                responseTimeout == null ? 90 : responseTimeout.toSeconds()
        );
        try {
            return client.execute(post, response -> {
                String raw = response.getEntity() == null
                        ? "{}"
                        : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                JsonNode parsed;
                try {
                    parsed = MAPPER.readTree(raw);
                } catch (Exception ex) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "Agent runtime returned an unexpected response.");
                }
                int statusCode = response.getCode();
                if (statusCode >= 200 && statusCode < 300) {
                    log.info("Agent runtime {} HTTP {}", payload.path("command").asText(""), statusCode);
                    return parsed;
                }
                if (statusCode == 401 || statusCode == 403) {
                    log.warn("Agent runtime auth failed with HTTP {}", statusCode);
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach the agent runtime.");
                }
                if (statusCode == 400) {
                    String message = parsed.path("message").asText("The agent runtime rejected that request.");
                    throw new ApiException(HttpStatus.BAD_REQUEST, message);
                }
                log.warn("Agent runtime HTTP {} message={}", statusCode, parsed.path("message").asText(""));
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach the agent runtime.");
            });
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("Agent runtime call failed for url={}: {}", url, ex.toString());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not reach the agent runtime at " + url + ". Error: " + ex.getMessage()
            );
        }
    }

    private static ObjectNode copyObject(JsonNode body) {
        if (body != null && body.isObject()) {
            return (ObjectNode) body.deepCopy();
        }
        return MAPPER.createObjectNode();
    }
}
