package com.talentserv.blink.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
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
            .disableAutomaticRetries()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(10))
                            .setSocketTimeout(Timeout.ofSeconds(90))
                            .build())
                    .build())
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setResponseTimeout(Timeout.ofSeconds(90))
                    .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                    .build())
            .build();
    private volatile LambdaClient lambdaClient;

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
        LambdaClient current = lambdaClient;
        if (current != null) {
            current.close();
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

        if (shouldInvokeLambda(url)) {
            return invokeLambda(payload, token.trim());
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
                if (statusCode == 503) {
                    throw new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            "The hosted planner hit the API Gateway 29s limit. Retry once, or use direct Lambda invoke."
                    );
                }
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

    private boolean shouldInvokeLambda(String url) {
        String lowered = url.trim().toLowerCase();
        if (lowered.contains("127.0.0.1") || lowered.contains("localhost")) {
            return false;
        }
        String functionName = properties.getAgentRuntimeLambdaFunction();
        if (functionName == null || functionName.isBlank()) {
            return false;
        }
        if (properties.getAwsAccessKeyId() == null || properties.getAwsAccessKeyId().isBlank()
                || properties.getAwsSecretAccessKey() == null || properties.getAwsSecretAccessKey().isBlank()) {
            return false;
        }
        return lowered.contains("execute-api") || lowered.contains("lambda-url") || lowered.contains("amazonaws.com");
    }

    private JsonNode invokeLambda(ObjectNode payload, String token) {
        String functionName = properties.getAgentRuntimeLambdaFunction().trim();
        ObjectNode event = MAPPER.createObjectNode();
        event.put("version", "2.0");
        event.put("routeKey", "$default");
        event.put("rawPath", "/");
        event.put("rawQueryString", "");
        ObjectNode headers = event.putObject("headers");
        headers.put("authorization", "Bearer " + token);
        headers.put("content-type", "application/json");
        headers.put("accept", "application/json");
        ObjectNode requestContext = event.putObject("requestContext");
        ObjectNode http = requestContext.putObject("http");
        http.put("method", "POST");
        http.put("path", "/");
        http.put("protocol", "HTTP/1.1");
        http.put("sourceIp", "127.0.0.1");
        event.put("body", payload.toString());
        event.put("isBase64Encoded", false);

        log.info("Invoking Lambda {} command={}", functionName, payload.path("command").asText(""));
        try {
            InvokeResponse response = lambdaClient().invoke(InvokeRequest.builder()
                    .functionName(functionName)
                    .payload(SdkBytes.fromUtf8String(event.toString()))
                    .build());
            String raw = response.payload() == null ? "{}" : response.payload().asUtf8String();
            if (response.functionError() != null && !response.functionError().isBlank()) {
                log.warn("Lambda {} function error {}: {}", functionName, response.functionError(), raw);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Agent runtime Lambda failed: " + response.functionError());
            }
            JsonNode envelope;
            try {
                envelope = MAPPER.readTree(raw);
            } catch (Exception ex) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Agent runtime returned an unexpected response.");
            }
            int statusCode = envelope.path("statusCode").asInt(200);
            String body = envelope.path("body").asText(raw);
            if (envelope.path("isBase64Encoded").asBoolean(false)) {
                body = new String(java.util.Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
            }
            JsonNode parsed;
            try {
                parsed = MAPPER.readTree(body);
            } catch (Exception ex) {
                parsed = envelope;
            }
            if (statusCode >= 200 && statusCode < 300) {
                log.info("Agent runtime {} Lambda HTTP {}", payload.path("command").asText(""), statusCode);
                return parsed;
            }
            if (statusCode == 400) {
                throw new ApiException(HttpStatus.BAD_REQUEST, parsed.path("message").asText("The agent runtime rejected that request."));
            }
            log.warn("Agent runtime Lambda HTTP {} message={}", statusCode, parsed.path("message").asText(""));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach the agent runtime.");
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Lambda invoke failed for {}: {}", functionName, ex.toString());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not reach the agent runtime via Lambda. Error: " + ex.getMessage()
            );
        }
    }

    private LambdaClient lambdaClient() {
        LambdaClient current = lambdaClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (lambdaClient == null) {
                lambdaClient = LambdaClient.builder()
                        .region(Region.of(properties.getAwsRegion() == null || properties.getAwsRegion().isBlank()
                                ? "us-west-2"
                                : properties.getAwsRegion().trim()))
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        properties.getAwsAccessKeyId().trim(),
                                        properties.getAwsSecretAccessKey().trim()
                                )
                        ))
                        .httpClientBuilder(UrlConnectionHttpClient.builder()
                                .connectionTimeout(Duration.ofSeconds(10))
                                .socketTimeout(Duration.ofSeconds(90)))
                        .overrideConfiguration(ClientOverrideConfiguration.builder()
                                .apiCallTimeout(Duration.ofSeconds(95))
                                .apiCallAttemptTimeout(Duration.ofSeconds(95))
                                .build())
                        .build();
            }
            return lambdaClient;
        }
    }

    private static ObjectNode copyObject(JsonNode body) {
        if (body != null && body.isObject()) {
            return (ObjectNode) body.deepCopy();
        }
        return MAPPER.createObjectNode();
    }
}
