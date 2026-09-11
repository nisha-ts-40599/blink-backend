package com.talentserv.blink.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.dto.CreateRepositoriesRequest;
import com.talentserv.blink.dto.CreateRepositoriesResponse;
import com.talentserv.blink.dto.IntegrationBindingRequest;
import com.talentserv.blink.dto.IntegrationConnectRequest;
import com.talentserv.blink.dto.IntegrationConnectResponse;
import com.talentserv.blink.dto.StoredIntegration;
import com.talentserv.blink.dto.JiraCreateIssuesRequest;
import com.talentserv.blink.dto.JiraCreateIssuesResponse;
import com.talentserv.blink.dto.JiraCreatedIssue;
import com.talentserv.blink.dto.JiraEpicSpec;
import com.talentserv.blink.dto.FigmaOAuthExchangeRequest;
import com.talentserv.blink.dto.FigmaOAuthUrlResponse;
import com.talentserv.blink.dto.FigmaProjectsRequest;
import com.talentserv.blink.dto.FigmaTeamsRequest;
import com.talentserv.blink.dto.GithubOAuthExchangeRequest;
import com.talentserv.blink.dto.GithubOAuthUrlResponse;
import com.talentserv.blink.dto.GithubOrgDto;
import com.talentserv.blink.dto.GithubOrgsRequest;
import com.talentserv.blink.dto.JiraOAuthExchangeRequest;
import com.talentserv.blink.dto.JiraOAuthUrlResponse;
import com.talentserv.blink.dto.JiraProjectDto;
import com.talentserv.blink.dto.JiraProjectsRequest;
import com.talentserv.blink.dto.JiraStorySpec;
import com.talentserv.blink.error.ApiException;

@Service
public class IntegrationConnectService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationConnectService.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern FIGMA_TEAM_ID = Pattern.compile("(?:/files)?/team/(\\d+)");
    private static final String FIGMA_DEFAULT_SCOPES =
            "current_user:read,file_content:read,file_metadata:read,projects:read";

    private final IntegrationHttpGateway http;
    private final BlinkProperties properties;
    private final ProjectIntegrationStore integrations;

    @Autowired
    public IntegrationConnectService(
            IntegrationHttpGateway http,
            BlinkProperties properties,
            ProjectIntegrationStore integrations
    ) {
        this.http = http;
        this.properties = properties != null ? properties : new BlinkProperties();
        this.integrations = integrations != null ? integrations : ProjectIntegrationStore.noop();
    }

    public IntegrationConnectService(IntegrationHttpGateway http, BlinkProperties properties) {
        this(http, properties, ProjectIntegrationStore.noop());
    }

    public IntegrationConnectService(IntegrationHttpGateway http) {
        this(http, new BlinkProperties(), ProjectIntegrationStore.noop());
    }

    public IntegrationConnectResponse connect(IntegrationConnectRequest request) {
        String provider = request.provider().trim().toLowerCase(Locale.ROOT);
        IntegrationConnectResponse result = switch (provider) {
            case "github" -> connectGitHub(request);
            case "figma" -> connectFigma(request);
            case "bitbucket" -> connectBitbucket(request);
            case "jira" -> connectJira(request);
            case "confluence" -> connectConfluence(request);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported provider.");
        };
        persist(
                parseProjectId(request.projectId()),
                result.provider(),
                result.account(),
                result.baseUrl() != null ? result.baseUrl() : request.baseUrl(),
                request.email(),
                request.username(),
                firstNonBlank(result.organization(), request.organization()),
                request.workspace(),
                result.projectKey() != null ? result.projectKey() : request.projectKey(),
                result.projectName(),
                request.spaceKey(),
                result.cloudId(),
                result.authType() != null ? result.authType() : "token",
                request.token(),
                null,
                null
        );
        return redact(result);
    }

    public GithubOAuthUrlResponse getGithubOAuthUrl() {
        return getGithubOAuthUrl(null, null);
    }

    public GithubOAuthUrlResponse getGithubOAuthUrl(String requestedRedirectUri, String publicApiBase) {
        String clientId = properties.getGithubClientId() == null ? "" : properties.getGithubClientId().trim();
        if (clientId.isBlank()) {
            return new GithubOAuthUrlResponse(
                    false,
                    null,
                    null,
                    null,
                    "GitHub OAuth is not configured. Set BLINK_GITHUB_CLIENT_ID and BLINK_GITHUB_CLIENT_SECRET on the server."
            );
        }
        String redirectUri = resolveGithubRedirectUri(requestedRedirectUri, publicApiBase);
        String scopes = firstNonBlank(properties.getGithubScopes(), "repo read:org user:email");
        String state = UUID.randomUUID().toString();
        String url = "https://github.com/login/oauth/authorize"
                + "?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(scopes)
                + "&state=" + encode(state)
                + "&allow_signup=false";
        return new GithubOAuthUrlResponse(true, url, clientId, redirectUri, "Ready for GitHub authorization.");
    }

    public IntegrationConnectResponse exchangeGithubOAuth(GithubOAuthExchangeRequest request) {
        String clientId = properties.getGithubClientId() == null ? "" : properties.getGithubClientId().trim();
        String clientSecret = properties.getGithubClientSecret() == null ? "" : properties.getGithubClientSecret().trim();
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GitHub OAuth credentials (client ID/secret) are not configured on the server.");
        }
        String code = required(request.code(), "Authorization code is required.");
        String redirectUri = resolveGithubRedirectUri(request.redirectUri(), null);
        log.info("Exchanging GitHub OAuth code redirectUri={}", redirectUri);

        String form = "client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri);
        IntegrationHttpGateway.IntegrationHttpResponse tokenRes = http.post(
                "https://github.com/login/oauth/access_token",
                Map.of("Accept", "application/json"),
                form,
                "application/x-www-form-urlencoded"
        );
        if (tokenRes.status() < 200 || tokenRes.status() >= 300) {
            log.warn(
                    "GitHub OAuth token exchange failed: status={} body={}",
                    tokenRes.status(),
                    abbreviate(tokenRes.body(), 500)
            );
            String err = firstText(tokenRes.body(), "error_description", "error", "message");
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "Failed to exchange GitHub code: " + (err.isBlank() ? "HTTP " + tokenRes.status() : err)
            );
        }
        String accessToken = firstText(tokenRes.body(), "access_token");
        if (accessToken.isBlank()) {
            String err = firstText(tokenRes.body(), "error_description", "error", "message");
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "GitHub response did not contain an access token"
                            + (err.isBlank() ? "." : ": " + err)
            );
        }

        IntegrationConnectResponse identified = identifyGitHub(accessToken, request.organization(), "oauth");
        persist(
                parseProjectId(request.projectId()),
                "github",
                identified.account(),
                "https://github.com",
                null,
                identified.account(),
                identified.organization(),
                null,
                null,
                null,
                null,
                null,
                "oauth",
                accessToken,
                null,
                null
        );
        return redact(identified);
    }

    public FigmaOAuthUrlResponse getFigmaOAuthUrl() {
        return getFigmaOAuthUrl(null, null);
    }

    public FigmaOAuthUrlResponse getFigmaOAuthUrl(String requestedRedirectUri, String publicApiBase) {
        String clientId = properties.getFigmaClientId() == null ? "" : properties.getFigmaClientId().trim();
        if (clientId.isBlank()) {
            return new FigmaOAuthUrlResponse(
                    false,
                    null,
                    null,
                    null,
                    "Figma OAuth is not configured. Set BLINK_FIGMA_CLIENT_ID and BLINK_FIGMA_CLIENT_SECRET on the server."
            );
        }
        String redirectUri = resolveFigmaRedirectUri(requestedRedirectUri, publicApiBase);
        String scopes = firstNonBlank(properties.getFigmaScopes(), FIGMA_DEFAULT_SCOPES);
        String state = UUID.randomUUID().toString();
        String url = "https://www.figma.com/oauth"
                + "?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(scopes)
                + "&state=" + encode(state)
                + "&response_type=code";
        return new FigmaOAuthUrlResponse(true, url, clientId, redirectUri, "Ready for Figma authorization.");
    }

    public IntegrationConnectResponse exchangeFigmaOAuth(FigmaOAuthExchangeRequest request) {
        String clientId = properties.getFigmaClientId() == null ? "" : properties.getFigmaClientId().trim();
        String clientSecret = properties.getFigmaClientSecret() == null ? "" : properties.getFigmaClientSecret().trim();
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Figma OAuth credentials (client ID/secret) are not configured on the server.");
        }
        String code = required(request.code(), "Authorization code is required.");
        String redirectUri = resolveFigmaRedirectUri(request.redirectUri(), null);
        log.info("Exchanging Figma OAuth code redirectUri={}", redirectUri);

        String form = "redirect_uri=" + encode(redirectUri)
                + "&code=" + encode(code)
                + "&grant_type=authorization_code";
        IntegrationHttpGateway.IntegrationHttpResponse tokenRes = http.post(
                "https://api.figma.com/v1/oauth/token",
                Map.of("Authorization", basic(clientId, clientSecret), "Accept", "application/json"),
                form,
                "application/x-www-form-urlencoded"
        );
        if (tokenRes.status() < 200 || tokenRes.status() >= 300) {
            log.warn(
                    "Figma OAuth token exchange failed: status={} body={}",
                    tokenRes.status(),
                    abbreviate(tokenRes.body(), 500)
            );
            String err = firstText(tokenRes.body(), "message", "error_description", "error");
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "Failed to exchange Figma code: " + (err.isBlank() ? "HTTP " + tokenRes.status() : err)
            );
        }
        String accessToken = firstText(tokenRes.body(), "access_token");
        if (accessToken.isBlank()) {
            String err = firstText(tokenRes.body(), "message", "error_description", "error");
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "Figma response did not contain an access token"
                            + (err.isBlank() ? "." : ": " + err)
            );
        }
        String refreshToken = firstText(tokenRes.body(), "refresh_token");
        Instant expiresAt = null;
        try {
            JsonNode tokenJsonNode = MAPPER.readTree(tokenRes.body());
            int expiresIn = tokenJsonNode.path("expires_in").asInt(0);
            if (expiresIn > 0) {
                expiresAt = Instant.now().plusSeconds(expiresIn);
            }
        } catch (Exception ignored) {
        }

        IntegrationConnectResponse identified = identifyFigma(accessToken, request.organization(), "oauth");
        persist(
                parseProjectId(request.projectId()),
                "figma",
                identified.account(),
                "https://www.figma.com",
                null,
                identified.account(),
                identified.organization(),
                null,
                identified.projectKey(),
                identified.projectName(),
                null,
                null,
                "oauth",
                accessToken,
                refreshToken.isBlank() ? null : refreshToken,
                expiresAt
        );
        return redact(identified);
    }

    public JiraOAuthUrlResponse getJiraOAuthUrl() {
        return getJiraOAuthUrl(null, null);
    }

    public JiraOAuthUrlResponse getJiraOAuthUrl(String requestedRedirectUri, String publicApiBase) {
        String clientId = properties.getJiraClientId() == null ? "" : properties.getJiraClientId().trim();
        if (clientId.isBlank()) {
            return new JiraOAuthUrlResponse(
                    false,
                    null,
                    null,
                    null,
                    "Jira OAuth is not configured. Set BLINK_JIRA_CLIENT_ID and BLINK_JIRA_CLIENT_SECRET, or connect using an API token."
            );
        }
        String redirectUri = resolveJiraRedirectUri(requestedRedirectUri, publicApiBase);
        String scopes = firstNonBlank(
                properties.getJiraScopes(),
                "read:jira-work write:jira-work read:jira-user read:me offline_access"
        );
        String state = UUID.randomUUID().toString();
        String url = "https://auth.atlassian.com/authorize"
                + "?audience=api.atlassian.com"
                + "&client_id=" + encode(clientId)
                + "&scope=" + encode(scopes)
                + "&redirect_uri=" + encode(redirectUri)
                + "&state=" + encode(state)
                + "&response_type=code"
                + "&prompt=consent";
        return new JiraOAuthUrlResponse(true, url, clientId, redirectUri, "Ready for Atlassian authorization.");
    }

    public IntegrationConnectResponse exchangeJiraOAuth(JiraOAuthExchangeRequest request) {
        String clientId = properties.getJiraClientId() == null ? "" : properties.getJiraClientId().trim();
        String clientSecret = properties.getJiraClientSecret() == null ? "" : properties.getJiraClientSecret().trim();
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Jira OAuth credentials (client ID/secret) are not configured on the server.");
        }
        String code = required(request.code(), "Authorization code is required.");
        String redirectUri = resolveJiraRedirectUri(request.redirectUri(), null);

        Map<String, String> tokenPayload = new LinkedHashMap<>();
        tokenPayload.put("grant_type", "authorization_code");
        tokenPayload.put("client_id", clientId);
        tokenPayload.put("client_secret", clientSecret);
        tokenPayload.put("code", code);
        tokenPayload.put("redirect_uri", redirectUri);
        String tokenJson = writeJson(tokenPayload);
        log.info("Exchanging Atlassian OAuth code redirectUri={}", redirectUri);

        IntegrationHttpGateway.IntegrationHttpResponse tokenRes = http.post(
                "https://auth.atlassian.com/oauth/token",
                Map.of("Accept", "application/json"),
                tokenJson,
                "application/json"
        );
        if (tokenRes.status() == 415) {
            log.warn("Atlassian OAuth JSON token exchange returned 415; retrying as form-urlencoded");
            String form = "grant_type=authorization_code"
                    + "&client_id=" + encode(clientId)
                    + "&client_secret=" + encode(clientSecret)
                    + "&code=" + encode(code)
                    + "&redirect_uri=" + encode(redirectUri);
            tokenRes = http.post(
                    "https://auth.atlassian.com/oauth/token",
                    Map.of("Accept", "application/json"),
                    form,
                    "application/x-www-form-urlencoded"
            );
        }
        if (tokenRes.status() < 200 || tokenRes.status() >= 300) {
            log.warn(
                    "Atlassian OAuth token exchange failed: status={} body={}",
                    tokenRes.status(),
                    abbreviate(tokenRes.body(), 500)
            );
            String err = firstText(tokenRes.body(), "error_description", "error", "message");
            if (err.isBlank() && tokenRes.body() != null && tokenRes.body().toLowerCase(Locale.ROOT).contains("unsupported media type")) {
                err = "Unsupported Media Type";
            }
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "Failed to exchange Atlassian code: " + (err.isBlank() ? "HTTP " + tokenRes.status() : err)
            );
        }

        String accessToken = firstText(tokenRes.body(), "access_token");
        if (accessToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Atlassian response did not contain an access token.");
        }
        String refreshToken = firstText(tokenRes.body(), "refresh_token");
        Instant expiresAt = null;
        try {
            JsonNode tokenJsonNode = MAPPER.readTree(tokenRes.body());
            int expiresIn = tokenJsonNode.path("expires_in").asInt(0);
            if (expiresIn > 0) {
                expiresAt = Instant.now().plusSeconds(expiresIn);
            }
        } catch (Exception ignored) {
        }

        IntegrationHttpGateway.IntegrationHttpResponse resourcesRes = getJson(
                "https://api.atlassian.com/oauth/token/accessible-resources",
                Map.of("Authorization", "Bearer " + accessToken, "Accept", "application/json")
        );
        String cloudId = "";
        String siteUrl = "";
        String siteName = "";
        try {
            JsonNode resources = MAPPER.readTree(resourcesRes.body());
            if (resources.isArray() && !resources.isEmpty()) {
                JsonNode site = resources.get(0);
                cloudId = site.path("id").asText("");
                siteUrl = site.path("url").asText("");
                siteName = site.path("name").asText("");
            }
        } catch (Exception ex) {
            log.warn("Failed to parse Atlassian accessible resources: {}", ex.toString());
        }
        if (cloudId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No accessible Jira Cloud sites found for this Atlassian account.");
        }

        String account = "Atlassian User";
        try {
            IntegrationHttpGateway.IntegrationHttpResponse me = http.get(
                    "https://api.atlassian.com/ex/jira/" + cloudId + "/rest/api/3/myself",
                    Map.of("Authorization", "Bearer " + accessToken)
            );
            if (me.status() == 200) {
                String found = firstText(me.body(), "displayName", "emailAddress");
                if (!found.isBlank()) {
                    account = found;
                }
            }
        } catch (Exception ignored) {
        }

        List<JiraProjectDto> projects = fetchJiraProjectsInternal(siteUrl, null, null, cloudId, accessToken);
        String firstProjectKey = projects.isEmpty() ? null : projects.get(0).key();
        String firstProjectName = projects.isEmpty() ? null : projects.get(0).name();
        String detail = "Connected with Atlassian OAuth as " + account + " to " + (siteName.isBlank() ? siteUrl : siteName)
                + (projects.isEmpty() ? "" : " (" + projects.size() + " projects available)");

        IntegrationConnectResponse connected = new IntegrationConnectResponse(
                true,
                "jira",
                account,
                detail,
                firstProjectKey,
                firstProjectName,
                siteUrl,
                cloudId,
                "oauth",
                accessToken,
                projects
        );
        persist(
                parseProjectId(request.projectId()),
                "jira",
                account,
                siteUrl,
                null,
                null,
                null,
                null,
                firstProjectKey,
                firstProjectName,
                null,
                cloudId,
                "oauth",
                accessToken,
                refreshToken,
                expiresAt
        );
        return redact(connected);
    }

    public List<JiraProjectDto> fetchJiraProjects(JiraProjectsRequest request) {
        if (request == null) {
            return List.of();
        }
        StoredIntegration stored = load(parseProjectId(request.projectId()), "jira").orElse(null);
        String baseUrl = firstNonBlank(request.baseUrl(), stored == null ? null : stored.baseUrl());
        String email = firstNonBlank(request.email(), stored == null ? null : stored.email());
        String token = firstNonBlank(request.token(), stored != null && !"oauth".equals(stored.authType()) ? stored.accessToken() : null);
        String cloudId = firstNonBlank(request.cloudId(), stored == null ? null : stored.cloudId());
        String accessToken = firstNonBlank(
                request.accessToken(),
                stored != null && "oauth".equals(stored.authType()) ? stored.accessToken() : null
        );
        return fetchJiraProjectsInternal(baseUrl, email, token, cloudId, accessToken);
    }

    public List<GithubOrgDto> fetchGithubOrgs(GithubOrgsRequest request) {
        if (request == null) {
            return List.of();
        }
        StoredIntegration stored = load(parseProjectId(request.projectId()), "github").orElse(null);
        String token = firstNonBlank(request.token(), stored == null ? null : stored.accessToken());
        if (token == null || token.isBlank()) {
            return List.of();
        }
        return fetchGithubOrgsInternal(token);
    }

    public List<GithubOrgDto> fetchFigmaTeams(FigmaTeamsRequest request) {
        if (request == null) {
            return List.of();
        }
        StoredIntegration stored = load(parseProjectId(request.projectId()), "figma").orElse(null);
        String token = firstNonBlank(request.token(), stored == null ? null : stored.accessToken());
        if (token == null || token.isBlank()) {
            return List.of();
        }
        return fetchFigmaTeamsInternal(token);
    }

    public List<JiraProjectDto> fetchFigmaProjects(FigmaProjectsRequest request) {
        if (request == null) {
            return List.of();
        }
        StoredIntegration stored = load(parseProjectId(request.projectId()), "figma").orElse(null);
        String token = firstNonBlank(request.token(), stored == null ? null : stored.accessToken());
        String teamId = parseFigmaTeamId(firstNonBlank(request.organization(), stored == null ? null : stored.organization()));
        if (token == null || token.isBlank() || teamId == null) {
            return List.of();
        }
        return fetchFigmaProjectsInternal(token, teamId);
    }

    private List<JiraProjectDto> fetchJiraProjectsInternal(
            String baseUrl,
            String email,
            String token,
            String cloudId,
            String accessToken
    ) {
        String url;
        Map<String, String> headers;
        if (cloudId != null && !cloudId.isBlank() && accessToken != null && !accessToken.isBlank()) {
            url = "https://api.atlassian.com/ex/jira/" + encode(cloudId) + "/rest/api/3/project";
            headers = Map.of("Authorization", "Bearer " + accessToken);
        } else if (baseUrl != null && !baseUrl.isBlank() && email != null && !email.isBlank() && token != null && !token.isBlank()) {
            String host = atlassianHost(baseUrl);
            url = "https://" + host + "/rest/api/3/project";
            headers = Map.of("Authorization", basic(email, token));
        } else {
            return List.of();
        }

        List<JiraProjectDto> projects = new ArrayList<>();
        try {
            IntegrationHttpGateway.IntegrationHttpResponse res = http.get(url, headers);
            if (res.status() == 200 && res.body() != null && !res.body().isBlank()) {
                JsonNode root = MAPPER.readTree(res.body());
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        String id = item.path("id").asText("");
                        String key = item.path("key").asText("");
                        String name = item.path("name").asText("");
                        String type = item.path("projectTypeKey").asText("");
                        String avatar = item.path("avatarUrls").path("48x48").asText("");
                        if (!key.isBlank()) {
                            projects.add(new JiraProjectDto(id, key, name, type, avatar));
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Could not retrieve Jira projects: {}", ex.toString());
        }
        return projects;
    }

    public JiraCreateIssuesResponse createJiraIssues(JiraCreateIssuesRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Jira create request is required.");
        }
        String projectKey = required(request.projectKey(), "Jira project key is required.");
        StoredIntegration stored = load(parseProjectId(request.projectId()), "jira").orElse(null);
        JiraCallContext ctx = resolveJiraCall(
                firstNonBlank(request.baseUrl(), stored == null ? null : stored.baseUrl()),
                firstNonBlank(request.email(), stored == null ? null : stored.email()),
                firstNonBlank(request.token(), stored != null && !"oauth".equals(stored.authType()) ? stored.accessToken() : null),
                firstNonBlank(request.cloudId(), stored == null ? null : stored.cloudId()),
                firstNonBlank(request.accessToken(), stored != null && "oauth".equals(stored.authType()) ? stored.accessToken() : null)
        );
        persist(
                parseProjectId(request.projectId()),
                "jira",
                stored == null ? null : stored.account(),
                stored == null ? null : stored.baseUrl(),
                stored == null ? null : stored.email(),
                null,
                null,
                null,
                projectKey,
                stored == null ? null : stored.projectName(),
                null,
                stored == null ? null : stored.cloudId(),
                stored == null ? "oauth" : stored.authType(),
                stored == null ? null : stored.accessToken(),
                stored == null ? null : stored.refreshToken(),
                stored == null ? null : stored.expiresAt()
        );
        List<JiraEpicSpec> epics = request.epics() == null ? List.of() : request.epics();
        List<JiraStorySpec> stories = request.stories() == null ? List.of() : request.stories();
        if (epics.isEmpty() && stories.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Add at least one epic or story to create in Jira.");
        }

        String epicType = resolveIssueType(ctx, "Epic", "Epic");
        String storyType = resolveIssueType(ctx, "Story", "User Story", "Task");
        FieldIds fields = discoverJiraFields(ctx);
        boolean teamManaged = isTeamManagedProject(ctx, projectKey);

        List<JiraCreatedIssue> created = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, String> epicKeys = new HashMap<>();

        for (JiraEpicSpec epic : epics) {
            if (epic == null || trimToNull(epic.title()) == null) {
                continue;
            }
            String description = buildEpicDescription(epic);
            try {
                JiraCreatedIssue item = createJiraIssue(
                        ctx,
                        projectKey,
                        epicType,
                        epic.title().trim(),
                        description,
                        null,
                        teamManaged ? null : fields.epicName(),
                        teamManaged ? null : epic.title().trim(),
                        "Epic",
                        epic.id()
                );
                created.add(item);
                if (item.jiraKey() != null && epic.id() != null) {
                    epicKeys.put(epic.id(), item.jiraKey());
                }
            } catch (ApiException ex) {
                errors.add(epic.title() + ": " + ex.getMessage());
                created.add(new JiraCreatedIssue(epic.id(), null, null, "Epic", "failed", ex.getMessage()));
            }
        }

        for (JiraStorySpec story : stories) {
            if (story == null || trimToNull(story.title()) == null) {
                continue;
            }
            String epicKey = resolveEpicKey(story, epics, epicKeys);
            String description = buildStoryDescription(story);
            try {
                JiraCreatedIssue item = createStoryUnderEpic(
                        ctx,
                        projectKey,
                        storyType,
                        story.title().trim(),
                        description,
                        epicKey,
                        fields.epicLink(),
                        teamManaged,
                        story.id()
                );
                created.add(item);
            } catch (ApiException ex) {
                errors.add(story.title() + ": " + ex.getMessage());
                created.add(new JiraCreatedIssue(story.id(), null, null, "Story", "failed", ex.getMessage()));
            }
        }

        long ok = created.stream().filter(item -> "created".equals(item.status())).count();
        String status = errors.isEmpty() ? "ok" : (ok > 0 ? "partial" : "error");
        String message = ok + " issue(s) created in Jira project " + projectKey
                + (errors.isEmpty() ? "." : " (" + errors.size() + " failed).");
        return new JiraCreateIssuesResponse(status, message, created, errors);
    }

    private JiraCreatedIssue createJiraIssue(
            JiraCallContext ctx,
            String projectKey,
            String issueType,
            String summary,
            String description,
            String parentKey,
            String extraFieldId,
            String extraFieldValue,
            String typeLabel,
            String sourceId
    ) {
        ObjectNode payload = MAPPER.createObjectNode();
        ObjectNode fieldsNode = payload.putObject("fields");
        fieldsNode.putObject("project").put("key", projectKey);
        fieldsNode.put("summary", summary.length() > 240 ? summary.substring(0, 240) : summary);
        fieldsNode.putObject("issuetype").put("name", issueType);
        if (description != null && !description.isBlank()) {
            fieldsNode.set("description", adfDocument(description));
        }
        if (parentKey != null && !parentKey.isBlank()) {
            fieldsNode.putObject("parent").put("key", parentKey);
        }
        if (extraFieldId != null && extraFieldValue != null && !extraFieldValue.isBlank()) {
            fieldsNode.put(extraFieldId, extraFieldValue);
        }
        IntegrationHttpGateway.IntegrationHttpResponse res = http.post(
                ctx.apiBase() + "/rest/api/3/issue",
                ctx.headers(),
                writeNode(payload)
        );
        if (res.status() < 200 || res.status() >= 300) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, jiraErrorMessage(res.body(), res.status()));
        }
        String key = firstText(res.body(), "key");
        String browse = ctx.browseBase() + "/browse/" + key;
        return new JiraCreatedIssue(sourceId, key, browse, typeLabel, "created", "Created " + key);
    }

    private JiraCreatedIssue createStoryUnderEpic(
            JiraCallContext ctx,
            String projectKey,
            String issueType,
            String summary,
            String description,
            String epicKey,
            String epicLinkFieldId,
            boolean teamManaged,
            String sourceId
    ) {
        JiraCreatedIssue created;
        if (teamManaged) {
            created = createJiraIssue(ctx, projectKey, issueType, summary, description, epicKey, null, null, "Story", sourceId);
        } else {
            created = createJiraIssue(ctx, projectKey, issueType, summary, description, null, epicLinkFieldId, epicKey, "Story", sourceId);
        }
        if (created.jiraKey() != null && epicKey != null && !epicKey.isBlank()) {
            boolean linked = linkStoryToEpic(ctx, epicKey, created.jiraKey(), epicLinkFieldId, teamManaged);
            if (!linked) {
                log.warn("Created {} but could not attach it under epic {}", created.jiraKey(), epicKey);
            }
        }
        return created;
    }

    private boolean linkStoryToEpic(
            JiraCallContext ctx,
            String epicKey,
            String storyKey,
            String epicLinkFieldId,
            boolean teamManaged
    ) {
        ObjectNode agileBody = MAPPER.createObjectNode();
        agileBody.putArray("issues").add(storyKey);
        IntegrationHttpGateway.IntegrationHttpResponse agile = http.post(
                ctx.apiBase() + "/rest/agile/1.0/epic/" + encode(epicKey) + "/issue",
                ctx.headers(),
                writeNode(agileBody)
        );
        if (agile.status() >= 200 && agile.status() < 300) {
            return true;
        }
        log.info("Agile epic-link for {} -> {} returned HTTP {}", storyKey, epicKey, agile.status());

        ObjectNode fieldsNode = MAPPER.createObjectNode();
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("fields", fieldsNode);
        if (teamManaged) {
            fieldsNode.putObject("parent").put("key", epicKey);
        } else if (epicLinkFieldId != null && !epicLinkFieldId.isBlank()) {
            fieldsNode.put(epicLinkFieldId, epicKey);
        } else {
            fieldsNode.putObject("parent").put("key", epicKey);
        }
        IntegrationHttpGateway.IntegrationHttpResponse update = http.put(
                ctx.apiBase() + "/rest/api/3/issue/" + encode(storyKey),
                ctx.headers(),
                writeNode(payload)
        );
        if (update.status() >= 200 && update.status() < 300) {
            return true;
        }
        log.warn("Issue update epic-link for {} -> {} returned HTTP {} body={}", storyKey, epicKey, update.status(), abbreviate(update.body(), 300));
        return false;
    }

    private String resolveEpicKey(JiraStorySpec story, List<JiraEpicSpec> epics, Map<String, String> epicKeys) {
        if (story.epicId() != null && !story.epicId().isBlank()) {
            String direct = epicKeys.get(story.epicId());
            if (direct != null) {
                return direct;
            }
        }
        if (story.id() != null) {
            for (JiraEpicSpec epic : epics) {
                if (epic != null && epic.storyIds() != null && epic.storyIds().contains(story.id())) {
                    String mapped = epicKeys.get(epic.id());
                    if (mapped != null) {
                        return mapped;
                    }
                }
            }
        }
        if (epicKeys.size() == 1) {
            return epicKeys.values().iterator().next();
        }
        return null;
    }

    private boolean isTeamManagedProject(JiraCallContext ctx, String projectKey) {
        try {
            IntegrationHttpGateway.IntegrationHttpResponse res = http.get(
                    ctx.apiBase() + "/rest/api/3/project/" + encode(projectKey),
                    ctx.headers()
            );
            if (res.status() == 200) {
                JsonNode project = MAPPER.readTree(res.body());
                if (project.path("simplified").asBoolean(false)) {
                    return true;
                }
                return "next-gen".equalsIgnoreCase(project.path("style").asText(""));
            }
        } catch (Exception ex) {
            log.warn("Could not read Jira project style for {}: {}", projectKey, ex.toString());
        }
        return false;
    }

    private String resolveIssueType(JiraCallContext ctx, String... preferredNames) {
        try {
            IntegrationHttpGateway.IntegrationHttpResponse res = http.get(ctx.apiBase() + "/rest/api/3/issuetype", ctx.headers());
            if (res.status() == 200) {
                JsonNode root = MAPPER.readTree(res.body());
                if (root.isArray()) {
                    for (String wanted : preferredNames) {
                        for (JsonNode item : root) {
                            if (wanted.equalsIgnoreCase(item.path("name").asText(""))) {
                                return item.path("name").asText(wanted);
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Could not list Jira issue types: {}", ex.toString());
        }
        return preferredNames[0];
    }

    private FieldIds discoverJiraFields(JiraCallContext ctx) {
        String epicName = null;
        String epicLink = null;
        try {
            IntegrationHttpGateway.IntegrationHttpResponse res = http.get(ctx.apiBase() + "/rest/api/3/field", ctx.headers());
            if (res.status() == 200) {
                JsonNode root = MAPPER.readTree(res.body());
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        String name = item.path("name").asText("");
                        String id = item.path("id").asText("");
                        String custom = item.path("schema").path("custom").asText("");
                        if ("Epic Name".equalsIgnoreCase(name) || custom.endsWith(":gh-epic-label")) {
                            epicName = id;
                        } else if ("Epic Link".equalsIgnoreCase(name) || custom.endsWith(":gh-epic-link")) {
                            epicLink = id;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Could not list Jira fields: {}", ex.toString());
        }
        return new FieldIds(epicName, epicLink);
    }

    private JiraCallContext resolveJiraCall(
            String baseUrl,
            String email,
            String token,
            String cloudId,
            String accessToken
    ) {
        if (cloudId != null && !cloudId.isBlank() && accessToken != null && !accessToken.isBlank()) {
            String browse = (baseUrl == null || baseUrl.isBlank()) ? "https://jira.atlassian.net" : baseUrl.replaceAll("/+$", "");
            return new JiraCallContext(
                    "https://api.atlassian.com/ex/jira/" + encode(cloudId),
                    Map.of("Authorization", "Bearer " + accessToken, "Accept", "application/json"),
                    browse
            );
        }
        if (baseUrl != null && !baseUrl.isBlank() && email != null && !email.isBlank() && token != null && !token.isBlank()) {
            String host = atlassianHost(baseUrl);
            return new JiraCallContext(
                    "https://" + host,
                    Map.of("Authorization", basic(email, token), "Accept", "application/json"),
                    "https://" + host
            );
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "Connect Jira with OAuth or an API token before creating issues.");
    }

    private ObjectNode adfDocument(String text) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("type", "doc");
        doc.put("version", 1);
        ArrayNode content = doc.putArray("content");
        String[] lines = text.split("\\R");
        for (String line : lines) {
            ObjectNode paragraph = content.addObject();
            paragraph.put("type", "paragraph");
            ArrayNode inner = paragraph.putArray("content");
            if (line.isBlank()) {
                continue;
            }
            ObjectNode run = inner.addObject();
            run.put("type", "text");
            run.put("text", line);
        }
        if (content.isEmpty()) {
            ObjectNode paragraph = content.addObject();
            paragraph.put("type", "paragraph");
            ObjectNode run = paragraph.putArray("content").addObject();
            run.put("type", "text");
            run.put("text", text);
        }
        return doc;
    }

    private String buildEpicDescription(JiraEpicSpec epic) {
        StringBuilder sb = new StringBuilder();
        if (epic.objective() != null && !epic.objective().isBlank()) {
            sb.append(epic.objective().trim());
        }
        if (epic.id() != null && !epic.id().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("Source epic: ").append(epic.id());
        }
        return sb.toString();
    }

    private String buildStoryDescription(JiraStorySpec story) {
        StringBuilder sb = new StringBuilder();
        if (story.asA() != null && story.iWant() != null) {
            sb.append("As a ").append(story.asA()).append(", I want ").append(story.iWant());
            if (story.soThat() != null && !story.soThat().isBlank()) {
                sb.append(" so that ").append(story.soThat());
            }
            sb.append('\n');
        } else if (story.objective() != null && !story.objective().isBlank()) {
            sb.append(story.objective().trim()).append('\n');
        }
        if (story.acceptanceCriteria() != null && !story.acceptanceCriteria().isEmpty()) {
            sb.append("Acceptance criteria:");
            for (String item : story.acceptanceCriteria()) {
                if (item != null && !item.isBlank()) {
                    sb.append('\n').append("- ").append(item.trim());
                }
            }
        }
        if (story.id() != null && !story.id().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("Source story: ").append(story.id());
        }
        return sb.toString();
    }

    private String jiraErrorMessage(String body, int status) {
        String fromArray = "";
        try {
            JsonNode node = MAPPER.readTree(body == null ? "{}" : body);
            JsonNode messages = node.path("errorMessages");
            if (messages.isArray() && !messages.isEmpty()) {
                fromArray = messages.get(0).asText("");
            }
            if (fromArray.isBlank()) {
                fromArray = firstText(body, "error", "error_description", "message");
            }
        } catch (Exception ignored) {
            fromArray = firstText(body, "error", "message");
        }
        return fromArray.isBlank() ? "Jira returned HTTP " + status + "." : fromArray;
    }

    private record JiraCallContext(String apiBase, Map<String, String> headers, String browseBase) {}

    private record FieldIds(String epicName, String epicLink) {}

    public CreateRepositoriesResponse createRepositories(CreateRepositoriesRequest request) {
        String provider = request.provider().trim().toLowerCase(Locale.ROOT);
        if (!"github".equals(provider)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only GitHub repository creation is supported.");
        }
        StoredIntegration stored = load(parseProjectId(request.projectId()), "github").orElse(null);
        String token = firstNonBlank(request.token(), stored == null ? null : stored.accessToken());
        token = required(token, "Connect GitHub on Integrations first (sign in with GitHub).");
        Map<String, String> headers = githubHeaders(token);
        getJson("https://api.github.com/user", headers);

        String org = firstNonBlank(request.organization(), stored == null ? null : stored.organization());
        String endpoint = org == null
                ? "https://api.github.com/user/repos"
                : "https://api.github.com/orgs/" + encode(org) + "/repos";

        List<CreateRepositoriesResponse.RepoResult> results = new ArrayList<>();
        for (CreateRepositoriesRequest.RepoSpec spec : request.repositories()) {
            results.add(createGitHubRepo(endpoint, headers, spec));
        }
        return new CreateRepositoriesResponse("github", results);
    }

    private CreateRepositoriesResponse.RepoResult createGitHubRepo(
            String endpoint,
            Map<String, String> headers,
            CreateRepositoriesRequest.RepoSpec spec
    ) {
        String name = required(spec.name(), "Repository name is required.");
        String body = repoJson(name, spec.description());
        IntegrationHttpGateway.IntegrationHttpResponse response = http.post(endpoint, headers, body);
        if (response.status() == 401 || response.status() == 403) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials or insufficient permission to create repositories.");
        }
        if (response.status() == 422 && containsIgnoreCase(response.body(), "already_exists")) {
            String url = firstText(response.body(), "html_url");
            if ("Connected account".equals(url)) {
                url = null;
            }
            return new CreateRepositoriesResponse.RepoResult(name, "exists", url, "Repository already exists.");
        }
        if (response.status() < 200 || response.status() >= 300) {
            return new CreateRepositoriesResponse.RepoResult(
                    name,
                    "failed",
                    null,
                    githubErrorMessage(response.body(), response.status())
            );
        }
        String htmlUrl = firstText(response.body(), "html_url");
        if ("Connected account".equals(htmlUrl)) {
            htmlUrl = "https://github.com/" + name;
        }
        return new CreateRepositoriesResponse.RepoResult(name, "created", htmlUrl, "Created " + htmlUrl);
    }

    private IntegrationConnectResponse connectGitHub(IntegrationConnectRequest request) {
        String token = required(request.token(), "GitHub personal access token is required.");
        return identifyGitHub(token, request.organization(), "token");
    }

    private IntegrationConnectResponse identifyGitHub(String token, String organization, String authType) {
        IntegrationHttpGateway.IntegrationHttpResponse user = getJson(
                "https://api.github.com/user",
                githubHeaders(token)
        );
        String account = firstText(user.body(), "login", "name");
        List<GithubOrgDto> organizations = fetchGithubOrgsInternal(token);
        String org = trimToNull(organization);
        if (org != null) {
            boolean known = organizations.stream().anyMatch(item -> !item.personal() && org.equalsIgnoreCase(item.login()));
            if (!known) {
                getJson(
                        "https://api.github.com/orgs/" + encode(org),
                        githubHeaders(token)
                );
            }
            return new IntegrationConnectResponse(
                    true,
                    "github",
                    account,
                    "Connected as " + account + " to " + org,
                    null,
                    null,
                    "https://github.com",
                    null,
                    authType,
                    token,
                    List.of(),
                    org,
                    organizations
            );
        }
        String detail = organizations.stream().anyMatch(item -> !item.personal())
                ? "Connected as " + account + " (" + organizations.size() + " destinations available)"
                : "Connected as " + account;
        return new IntegrationConnectResponse(
                true,
                "github",
                account,
                detail,
                null,
                null,
                "https://github.com",
                null,
                authType,
                token,
                List.of(),
                null,
                organizations
        );
    }

    private List<GithubOrgDto> fetchGithubOrgsInternal(String token) {
        Map<String, String> headers = githubHeaders(token);
        List<GithubOrgDto> organizations = new ArrayList<>();
        try {
            IntegrationHttpGateway.IntegrationHttpResponse profile = http.get("https://api.github.com/user", headers);
            if (profile.status() >= 200 && profile.status() < 300) {
                String login = firstText(profile.body(), "login");
                String avatar = firstText(profile.body(), "avatar_url");
                if (!login.isBlank()) {
                    organizations.add(new GithubOrgDto(login, "Personal (" + login + ")", avatar, true));
                }
            }
        } catch (Exception ex) {
            log.warn("Could not retrieve GitHub user for organization list: {}", ex.toString());
        }
        try {
            IntegrationHttpGateway.IntegrationHttpResponse res = http.get(
                    "https://api.github.com/user/orgs?per_page=100",
                    headers
            );
            if (res.status() == 200 && res.body() != null && !res.body().isBlank()) {
                JsonNode root = MAPPER.readTree(res.body());
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        String login = item.path("login").asText("");
                        if (login.isBlank()) {
                            continue;
                        }
                        String name = firstNonBlank(item.path("name").asText(""), login);
                        String avatar = item.path("avatar_url").asText("");
                        organizations.add(new GithubOrgDto(login, name, avatar, false));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Could not retrieve GitHub organizations: {}", ex.toString());
        }
        return organizations;
    }

    private IntegrationConnectResponse connectFigma(IntegrationConnectRequest request) {
        String token = required(request.token(), "Figma personal access token is required.");
        return identifyFigma(token, request.organization(), "token");
    }

    private IntegrationConnectResponse identifyFigma(String token, String organization, String authType) {
        IntegrationHttpGateway.IntegrationHttpResponse me = getJson(
                "https://api.figma.com/v1/me",
                figmaHeaders(token)
        );
        String account = firstNonBlank(
                firstText(me.body(), "handle", "email"),
                firstText(me.body(), "id")
        );
        if (account == null || account.isBlank()) {
            account = "Figma user";
        }
        List<GithubOrgDto> teams = parseFigmaTeams(me.body());
        if (teams.isEmpty()) {
            teams = fetchFigmaTeamsInternal(token);
        }
        String requestedTeamId = parseFigmaTeamId(organization);
        List<GithubOrgDto> resolvedTeams = teams;
        if (requestedTeamId != null) {
            boolean known = resolvedTeams.stream().anyMatch(item -> requestedTeamId.equals(item.login()));
            if (!known) {
                resolvedTeams = new ArrayList<>(resolvedTeams);
                resolvedTeams.add(new GithubOrgDto(requestedTeamId, "Team " + requestedTeamId, null, false));
            }
        }
        final String teamId = requestedTeamId != null
                ? requestedTeamId
                : (resolvedTeams.size() == 1 ? resolvedTeams.get(0).login() : null);
        List<JiraProjectDto> projects = teamId == null ? List.of() : fetchFigmaProjectsInternal(token, teamId);
        String projectKey = projects.isEmpty() ? null : projects.get(0).key();
        String projectName = projects.isEmpty() ? null : projects.get(0).name();
        String teamName = teamId == null ? null : resolvedTeams.stream()
                .filter(item -> teamId.equals(item.login()))
                .map(GithubOrgDto::name)
                .findFirst()
                .orElse(teamId);
        String detail;
        if (teamId != null && projectName != null) {
            detail = "Connected as " + account + " to " + teamName + " / " + projectName;
        } else if (teamId != null) {
            detail = "Connected as " + account + " to team " + teamName;
        } else if (!resolvedTeams.isEmpty()) {
            detail = "Connected as " + account + " (" + resolvedTeams.size() + " teams available)";
        } else {
            detail = "Connected as " + account + ". Paste a Figma team URL to bind a team.";
        }
        return new IntegrationConnectResponse(
                true,
                "figma",
                account,
                detail,
                projectKey,
                projectName,
                "https://www.figma.com",
                null,
                authType,
                token,
                projects,
                teamId,
                resolvedTeams
        );
    }

    private List<GithubOrgDto> fetchFigmaTeamsInternal(String token) {
        try {
            IntegrationHttpGateway.IntegrationHttpResponse me = http.get(
                    "https://api.figma.com/v1/me",
                    figmaHeaders(token)
            );
            if (me.status() >= 200 && me.status() < 300) {
                return parseFigmaTeams(me.body());
            }
        } catch (Exception ex) {
            log.warn("Could not retrieve Figma teams: {}", ex.toString());
        }
        return List.of();
    }

    private List<GithubOrgDto> parseFigmaTeams(String body) {
        List<GithubOrgDto> teams = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return teams;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode list = root.path("teams");
            if (!list.isArray()) {
                list = root.path("data").path("teams");
            }
            if (list.isArray()) {
                for (JsonNode item : list) {
                    String id = item.path("id").asText("");
                    if (id.isBlank()) {
                        continue;
                    }
                    String name = firstNonBlank(item.path("name").asText(""), "Team " + id);
                    String avatar = item.path("img_url").asText(item.path("thumbnailUrl").asText(""));
                    teams.add(new GithubOrgDto(id, name, avatar.isBlank() ? null : avatar, false));
                }
            }
        } catch (Exception ex) {
            log.warn("Could not parse Figma teams: {}", ex.toString());
        }
        return teams;
    }

    private List<JiraProjectDto> fetchFigmaProjectsInternal(String token, String teamId) {
        if (token == null || token.isBlank() || teamId == null || teamId.isBlank()) {
            return List.of();
        }
        List<JiraProjectDto> projects = new ArrayList<>();
        try {
            IntegrationHttpGateway.IntegrationHttpResponse res = http.get(
                    "https://api.figma.com/v1/teams/" + encode(teamId) + "/projects",
                    figmaHeaders(token)
            );
            if (res.status() != 200 || res.body() == null || res.body().isBlank()) {
                return projects;
            }
            JsonNode root = MAPPER.readTree(res.body());
            JsonNode list = root.path("projects");
            if (list.isArray()) {
                for (JsonNode item : list) {
                    String id = item.path("id").asText("");
                    if (id.isBlank()) {
                        continue;
                    }
                    String name = firstNonBlank(item.path("name").asText(""), id);
                    projects.add(new JiraProjectDto(id, id, name, null, null));
                }
            }
        } catch (Exception ex) {
            log.warn("Could not retrieve Figma projects for team {}: {}", teamId, ex.toString());
        }
        return projects;
    }

    private String parseFigmaTeamId(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.matches("\\d+")) {
            return trimmed;
        }
        Matcher matcher = FIGMA_TEAM_ID.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return trimmed;
    }

    private IntegrationConnectResponse connectBitbucket(IntegrationConnectRequest request) {
        String username = required(request.username(), "Bitbucket username is required.");
        String token = required(request.token(), "Bitbucket app password is required.");
        String workspace = required(request.workspace(), "Bitbucket workspace is required.");
        Map<String, String> headers = Map.of("Authorization", basic(username, token));
        IntegrationHttpGateway.IntegrationHttpResponse user = getJson("https://api.bitbucket.org/2.0/user", headers);
        String account = firstText(user.body(), "display_name", "username");
        getJson("https://api.bitbucket.org/2.0/workspaces/" + encode(workspace), headers);
        return new IntegrationConnectResponse(true, "bitbucket", account, "Connected as " + account + " to " + workspace);
    }

    private IntegrationConnectResponse connectJira(IntegrationConnectRequest request) {
        String host = atlassianHost(request.baseUrl());
        String email = required(request.email(), "Atlassian account email is required.");
        String token = required(request.token(), "Atlassian API token is required.");
        Map<String, String> headers = Map.of("Authorization", basic(email, token));
        IntegrationHttpGateway.IntegrationHttpResponse me = getJson("https://" + host + "/rest/api/3/myself", headers);
        String account = firstText(me.body(), "displayName", "emailAddress");
        String projectKey = trimToNull(request.projectKey());

        List<JiraProjectDto> projects = fetchJiraProjectsInternal("https://" + host, email, token, null, null);
        String projectName = null;

        if (projectKey != null) {
            getJson("https://" + host + "/rest/api/3/project/" + encode(projectKey), headers);
            for (JiraProjectDto p : projects) {
                if (projectKey.equalsIgnoreCase(p.key())) {
                    projectName = p.name();
                    break;
                }
            }
            String target = projectName != null ? projectName + " (" + projectKey + ")" : "project " + projectKey;
            return new IntegrationConnectResponse(
                    true, "jira", account, "Connected as " + account + " to " + target,
                    projectKey, projectName, "https://" + host, null, "token", token, projects
            );
        }

        String detail = projects.isEmpty()
                ? "Connected as " + account
                : "Connected as " + account + " (" + projects.size() + " projects available)";
        return new IntegrationConnectResponse(
                true, "jira", account, detail,
                null, null, "https://" + host, null, "token", token, projects
        );
    }

    private IntegrationConnectResponse connectConfluence(IntegrationConnectRequest request) {
        String host = atlassianHost(request.baseUrl());
        String email = required(request.email(), "Atlassian account email is required.");
        String token = required(request.token(), "Atlassian API token is required.");
        Map<String, String> headers = Map.of("Authorization", basic(email, token));
        IntegrationHttpGateway.IntegrationHttpResponse me = getJson(
                "https://" + host + "/wiki/rest/api/user/current",
                headers
        );
        String account = firstText(me.body(), "displayName", "username", "accountId");
        String spaceKey = trimToNull(request.spaceKey());
        if (spaceKey != null) {
            getJson("https://" + host + "/wiki/rest/api/space/" + encode(spaceKey), headers);
            return new IntegrationConnectResponse(true, "confluence", account, "Connected as " + account + " to space " + spaceKey);
        }
        return new IntegrationConnectResponse(true, "confluence", account, "Connected as " + account);
    }

    private IntegrationHttpGateway.IntegrationHttpResponse getJson(String url, Map<String, String> headers) {
        IntegrationHttpGateway.IntegrationHttpResponse response = http.get(url, headers);
        if (response.status() == 401 || response.status() == 403) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials or insufficient permission.");
        }
        if (response.status() == 404) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Account, organization, project, or space was not found.");
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Provider returned HTTP " + response.status() + ".");
        }
        return response;
    }

    private String writeJson(Map<String, String> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build Atlassian token request.");
        }
    }

    private String writeNode(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not build Jira issue request.");
        }
    }

    /**
     * Extract a JSON string field without regex. The previous nested-quantifier pattern
     * StackOverflowError'd on Atlassian HTML error pages (many quotes).
     */
    private String firstText(String body, String... fields) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            for (String field : fields) {
                JsonNode value = node.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return value.asText().trim();
                }
            }
        } catch (Exception ignored) {
            // Non-JSON bodies (HTML error pages) fall through to a linear scan.
        }
        for (String field : fields) {
            String found = scanJsonStringField(body, field);
            if (!found.isBlank()) {
                return found;
            }
        }
        return "";
    }

    private static String scanJsonStringField(String source, String field) {
        String needle = "\"" + field + "\"";
        int idx = source.indexOf(needle);
        if (idx < 0) {
            return "";
        }
        int colon = source.indexOf(':', idx + needle.length());
        if (colon < 0) {
            return "";
        }
        int start = source.indexOf('"', colon + 1);
        if (start < 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean escape = false;
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escape) {
                out.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return out.toString().trim();
            } else {
                out.append(c);
            }
        }
        return "";
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }

    private String atlassianHost(String baseUrl) {
        String raw = required(baseUrl, "Atlassian Cloud URL is required.");
        String clean = raw.trim();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://" + clean;
        }
        try {
            URI uri = URI.create(clean);
            String host = uri.getHost();
            if (host == null) {
                throw new IllegalArgumentException();
            }
            host = host.toLowerCase(Locale.ROOT);
            if (!host.endsWith(".atlassian.net")) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "Blink only connects to Atlassian Cloud (*.atlassian.net) sites."
                );
            }
            return host;
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid Atlassian Cloud URL.");
        }
    }

    public IntegrationConnectResponse saveBinding(IntegrationBindingRequest request) {
        Long projectId = parseProjectId(request.projectId());
        if (projectId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Project id is required to save the integration selection.");
        }
        String provider = request.provider().trim().toLowerCase(Locale.ROOT);
        StoredIntegration stored = load(projectId, provider).orElseThrow(
                () -> new ApiException(HttpStatus.BAD_REQUEST, "Connect " + provider + " before saving the project selection.")
        );
        StoredIntegration updated = stored.withProjectKey(
                trimToNull(request.projectKey()) == null ? stored.projectKey() : request.projectKey().trim(),
                firstNonBlank(request.projectName(), stored.projectName())
        );
        if ("github".equals(provider)) {
            updated = updated.withOrganization(trimToNull(request.organization()));
        }
        if ("figma".equals(provider)) {
            updated = updated.withOrganization(parseFigmaTeamId(request.organization()));
        }
        if ("confluence".equals(provider) && trimToNull(request.spaceKey()) != null) {
            updated = new StoredIntegration(
                    updated.projectId(), updated.provider(), updated.account(), updated.baseUrl(), updated.email(),
                    updated.username(), updated.organization(), updated.workspace(), updated.projectKey(),
                    updated.projectName(), request.spaceKey().trim(), updated.cloudId(), updated.authType(),
                    updated.accessToken(), updated.refreshToken(), updated.expiresAt()
            );
        }
        integrations.upsert(updated);
        return new IntegrationConnectResponse(
                true,
                updated.provider(),
                updated.account(),
                "Saved " + provider + " binding",
                updated.projectKey(),
                updated.projectName(),
                updated.baseUrl(),
                updated.cloudId(),
                updated.authType(),
                null,
                List.of(),
                updated.organization(),
                List.of()
        );
    }

    private void persist(
            Long projectId,
            String provider,
            String account,
            String baseUrl,
            String email,
            String username,
            String organization,
            String workspace,
            String projectKey,
            String projectName,
            String spaceKey,
            String cloudId,
            String authType,
            String accessToken,
            String refreshToken,
            Instant expiresAt
    ) {
        if (projectId == null || provider == null || provider.isBlank()) {
            return;
        }
        StoredIntegration existing = load(projectId, provider).orElse(null);
        integrations.upsert(new StoredIntegration(
                projectId,
                provider.trim().toLowerCase(Locale.ROOT),
                firstNonBlank(account, existing == null ? null : existing.account()),
                firstNonBlank(baseUrl, existing == null ? null : existing.baseUrl()),
                firstNonBlank(email, existing == null ? null : existing.email()),
                firstNonBlank(username, existing == null ? null : existing.username()),
                firstNonBlank(organization, existing == null ? null : existing.organization()),
                firstNonBlank(workspace, existing == null ? null : existing.workspace()),
                firstNonBlank(projectKey, existing == null ? null : existing.projectKey()),
                firstNonBlank(projectName, existing == null ? null : existing.projectName()),
                firstNonBlank(spaceKey, existing == null ? null : existing.spaceKey()),
                firstNonBlank(cloudId, existing == null ? null : existing.cloudId()),
                firstNonBlank(authType, existing == null ? null : existing.authType()),
                firstNonBlank(accessToken, existing == null ? null : existing.accessToken()),
                firstNonBlank(refreshToken, existing == null ? null : existing.refreshToken()),
                expiresAt != null ? expiresAt : (existing == null ? null : existing.expiresAt())
        ));
        log.info("Stored encrypted {} credentials for projectId={}", provider, projectId);
    }

    private Optional<StoredIntegration> load(Long projectId, String provider) {
        if (projectId == null || provider == null) {
            return Optional.empty();
        }
        return integrations.find(projectId, provider);
    }

    private String resolveGithubRedirectUri(String requested, String publicApiBase) {
        return OAuthRedirectResolver.resolve(
                "github",
                requested,
                publicApiBase,
                properties.getGithubRedirectUri(),
                properties.getCorsOrigins()
        );
    }

    private String resolveFigmaRedirectUri(String requested, String publicApiBase) {
        return OAuthRedirectResolver.resolve(
                "figma",
                requested,
                publicApiBase,
                properties.getFigmaRedirectUri(),
                properties.getCorsOrigins()
        );
    }

    private String resolveJiraRedirectUri(String requested, String publicApiBase) {
        return OAuthRedirectResolver.resolve(
                "jira",
                requested,
                publicApiBase,
                properties.getJiraRedirectUri(),
                properties.getCorsOrigins()
        );
    }

    private static IntegrationConnectResponse redact(IntegrationConnectResponse result) {
        if (result == null) {
            return null;
        }
        return new IntegrationConnectResponse(
                result.connected(),
                result.provider(),
                result.account(),
                result.detail(),
                result.projectKey(),
                result.projectName(),
                result.baseUrl(),
                result.cloudId(),
                result.authType(),
                null,
                result.projects() == null ? List.of() : result.projects(),
                result.organization(),
                result.organizations() == null ? List.of() : result.organizations()
        );
    }

    private static Long parseProjectId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private Map<String, String> figmaHeaders(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    private Map<String, String> githubHeaders(String token) {
        return Map.of(
                "Authorization", "Bearer " + token,
                "Accept", "application/vnd.github+json"
        );
    }

    private String repoJson(String name, String description) {
        String desc = description == null ? "" : description.replace("\"", "\\\"");
        return "{\"name\":\"" + name.replace("\"", "\\\"") + "\",\"description\":\"" + desc + "\",\"private\":true}";
    }

    private String githubErrorMessage(String body, int status) {
        String message = firstText(body, "message");
        if (message.isBlank()) {
            return "GitHub returned HTTP " + status + ".";
        }
        return message;
    }

    private boolean containsIgnoreCase(String source, String needle) {
        if (source == null || needle == null) {
            return false;
        }
        return source.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private String basic(String username, String password) {
        String pair = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String required(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
        return trimmed;
    }
}
