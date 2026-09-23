package com.talentserv.blink.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import com.talentserv.blink.dto.BlinkJiraIssueDeleteResponse;
import com.talentserv.blink.dto.BlinkJiraIssueListResponse;
import com.talentserv.blink.dto.BlinkJiraIssueResponse;
import com.talentserv.blink.dto.CreateRepositoriesRequest;
import com.talentserv.blink.dto.CreateRepositoriesResponse;
import com.talentserv.blink.dto.IntegrationBindingRequest;
import com.talentserv.blink.dto.IntegrationConnectRequest;
import com.talentserv.blink.dto.IntegrationConnectResponse;
import com.talentserv.blink.dto.StoredIntegration;
import com.talentserv.blink.dto.JiraCreateIssuesRequest;
import com.talentserv.blink.dto.JiraCreateIssuesResponse;
import com.talentserv.blink.dto.JiraCreatedIssue;
import com.talentserv.blink.dto.JiraDeleteIssuesRequest;
import com.talentserv.blink.dto.JiraIssueStatusItem;
import com.talentserv.blink.dto.JiraIssueStatusesRequest;
import com.talentserv.blink.dto.JiraIssueStatusesResponse;
import com.talentserv.blink.dto.JiraIssueTransitionRequest;
import com.talentserv.blink.dto.JiraCommentCreateRequest;
import com.talentserv.blink.dto.JiraCommentCreateResponse;
import com.talentserv.blink.dto.JiraCommentPollRequest;
import com.talentserv.blink.dto.JiraCommentPollResponse;
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
    /**
     * Subset every new Figma OAuth app can grant. Extra checkboxes on the Figma app
     * are fine; requesting folders/selections/library/webhooks returns
     * "Invalid scopes for app" when those are missing from the app.
     */
    private static final String FIGMA_DEFAULT_SCOPES =
            "current_user:read,file_content:read,file_metadata:read";
    private static final Pattern SOURCE_EPIC_LINE = Pattern.compile("(?m)^Source epic:\\s*(\\S+)\\s*$");
    private static final Pattern SOURCE_STORY_LINE = Pattern.compile("(?m)^Source story:\\s*(\\S+)\\s*$");
    private static final Pattern JIRA_ISSUE_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*-\\d+$");
    private static final Pattern JIRA_PROJECT_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]+$");
    private static final int JIRA_SEARCH_PAGE = 50;
    private static final int JIRA_SEARCH_MAX = 500;

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
        Long projectId = parseProjectId(request.projectId());
        StoredIntegration stored = resolveStoredJira(projectId);
        boolean oauth = storedJiraIsOAuth(stored);
        JiraCallContext ctx = resolveJiraCall(
                firstNonBlank(request.baseUrl(), stored == null ? null : stored.baseUrl()),
                firstNonBlank(request.email(), stored == null ? null : stored.email()),
                firstNonBlank(request.token(), stored != null && !oauth ? stored.accessToken() : null),
                firstNonBlank(request.cloudId(), stored == null ? null : stored.cloudId()),
                firstNonBlank(request.accessToken(), stored != null && oauth ? stored.accessToken() : null)
        );
        if (stored != null) {
            persist(
                    projectId,
                    "jira",
                    stored.account(),
                    stored.baseUrl(),
                    stored.email(),
                    stored.username(),
                    stored.organization(),
                    stored.workspace(),
                    projectKey,
                    stored.projectName(),
                    stored.spaceKey(),
                    stored.cloudId(),
                    oauth ? "oauth" : stored.authType(),
                    stored.accessToken(),
                    stored.refreshToken(),
                    stored.expiresAt()
            );
        }
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

    /**
     * Lists Jira issues Blink created for this Blink project (description markers only).
     * Never returns unmarked issues from the shared Jira project.
     */
    public BlinkJiraIssueListResponse listBlinkJiraIssues(String projectIdRaw) {
        Long projectId = requireBlinkProjectId(projectIdRaw);
        StoredIntegration stored = load(projectId, "jira").orElse(null);
        if (stored == null) {
            return new BlinkJiraIssueListResponse(
                    false, projectId, null, null, List.of(),
                    "Connect Jira for this Blink project first."
            );
        }
        String projectKey = trimToNull(stored.projectKey());
        if (projectKey == null) {
            return new BlinkJiraIssueListResponse(
                    false, projectId, null, null, List.of(),
                    "Pick a Jira project on the integrations step before resetting issues."
            );
        }
        JiraCallContext ctx = resolveStoredJira(stored);
        List<BlinkJiraIssueResponse> issues = searchBlinkMarkedIssues(ctx, projectKey);
        return new BlinkJiraIssueListResponse(
                true,
                projectId,
                projectKey,
                ctx.browseBase(),
                issues,
                issues.isEmpty()
                        ? "No Blink-marked issues in Jira project " + projectKey + "."
                        : issues.size() + " Blink-marked issue(s) in " + projectKey + "."
        );
    }

    public BlinkJiraIssueDeleteResponse deleteBlinkJiraIssue(String projectIdRaw, String issueKeyRaw) {
        Long projectId = requireBlinkProjectId(projectIdRaw);
        String issueKey = required(issueKeyRaw, "Jira issue key is required.");
        StoredIntegration stored = requireStoredJira(projectId);
        JiraCallContext ctx = resolveStoredJira(stored);
        String projectKey = stored.projectKey().trim();

        BlinkJiraIssueResponse marked = fetchBlinkMarkedIssue(ctx, projectKey, issueKey);
        if (marked == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Issue is not a Blink-marked Jira item (missing Source epic:/Source story:).");
        }
        if ("epic".equals(marked.sourceKind())) {
            List<String> foreignChildren = new ArrayList<>();
            for (JsonNode child : listChildrenOfEpic(ctx, marked.key())) {
                String childKey = trimToNull(child.path("key").asText(null));
                if (childKey == null) {
                    continue;
                }
                String plain = adfToPlainText(child.path("fields").path("description"));
                if (blinkSourceStoryId(plain) != null || blinkSourceEpicId(plain) != null) {
                    deleteJiraIssue(ctx, childKey);
                } else {
                    foreignChildren.add(childKey);
                }
            }
            if (!foreignChildren.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Epic " + marked.key() + " still has non-Blink children: "
                                + String.join(", ", foreignChildren.stream().limit(8).toList()));
            }
        }
        deleteJiraIssue(ctx, marked.key());
        return new BlinkJiraIssueDeleteResponse(1, 0, List.of(marked.key()), List.of(), List.of());
    }

    public BlinkJiraIssueDeleteResponse deleteAllBlinkJiraIssues(String projectIdRaw) {
        Long projectId = requireBlinkProjectId(projectIdRaw);
        StoredIntegration stored = requireStoredJira(projectId);
        JiraCallContext ctx = resolveStoredJira(stored);
        String projectKey = stored.projectKey().trim();

        List<BlinkJiraIssueResponse> issues = searchBlinkMarkedIssues(ctx, projectKey);
        List<BlinkJiraIssueResponse> stories = issues.stream()
                .filter(item -> "story".equals(item.sourceKind()))
                .toList();
        List<BlinkJiraIssueResponse> epics = issues.stream()
                .filter(item -> "epic".equals(item.sourceKind()))
                .toList();

        List<String> deleted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (BlinkJiraIssueResponse story : stories) {
            try {
                BlinkJiraIssueResponse again = fetchBlinkMarkedIssue(ctx, projectKey, story.key());
                if (again == null) {
                    skipped.add(story.key() + " (not Blink-marked)");
                    continue;
                }
                deleteJiraIssue(ctx, story.key());
                deleted.add(story.key());
            } catch (ApiException ex) {
                errors.add(story.key() + ": " + ex.getMessage());
            }
        }

        for (BlinkJiraIssueResponse epic : epics) {
            try {
                BlinkJiraIssueResponse again = fetchBlinkMarkedIssue(ctx, projectKey, epic.key());
                if (again == null) {
                    skipped.add(epic.key() + " (not Blink-marked)");
                    continue;
                }
                List<String> foreignChildren = unmarkedChildrenOf(ctx, epic.key());
                if (!foreignChildren.isEmpty()) {
                    skipped.add(epic.key() + " (has non-Blink children)");
                    continue;
                }
                deleteJiraIssue(ctx, epic.key());
                deleted.add(epic.key());
            } catch (ApiException ex) {
                errors.add(epic.key() + ": " + ex.getMessage());
            }
        }

        log.info(
                "Deleted Blink Jira issues projectId={} jiraProject={} deleted={} skipped={} errors={}",
                projectId, projectKey, deleted.size(), skipped.size(), errors.size()
        );
        return new BlinkJiraIssueDeleteResponse(
                deleted.size(),
                skipped.size(),
                List.copyOf(deleted),
                List.copyOf(skipped),
                List.copyOf(errors)
        );
    }

    public BlinkJiraIssueDeleteResponse deleteJiraIssuesByKeys(JiraDeleteIssuesRequest request) {
        if (request == null || request.issueKeys() == null || request.issueKeys().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Add at least one Jira issue key to delete.");
        }
        Long projectId = requireBlinkProjectId(request.projectId());
        StoredIntegration stored = requireStoredJira(projectId);
        JiraCallContext ctx = resolveStoredJira(stored);

        LinkedHashSet<String> requested = new LinkedHashSet<>();
        for (String raw : request.issueKeys()) {
            if (raw != null && !raw.isBlank()) {
                requested.add(raw.trim());
            }
        }
        if (requested.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Add at least one Jira issue key to delete.");
        }

        List<String> deleted = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> done = new HashSet<>();
        List<String> pending = new ArrayList<>(requested);
        for (int pass = 0; pass < 4 && !pending.isEmpty(); pass++) {
            List<String> next = new ArrayList<>();
            boolean progress = false;
            for (String key : pending) {
                if (!done.add(key)) {
                    continue;
                }
                try {
                    deleteJiraIssue(ctx, key, false);
                    deleted.add(key);
                    progress = true;
                } catch (ApiException ex) {
                    String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
                    if (ex.getStatus() == HttpStatus.NOT_FOUND || message.contains("404") || message.contains("does not exist")) {
                        deleted.add(key);
                        progress = true;
                        continue;
                    }
                    if (jiraDeleteBlockedByOtherIssues(ex) && pass < 3) {
                        done.remove(key);
                        next.add(key);
                        continue;
                    }
                    if (jiraDeleteBlockedByOtherIssues(ex)) {
                        errors.add(key + ": left in Jira because it still has other issues not created on this screen.");
                        continue;
                    }
                    errors.add(key + ": " + ex.getMessage());
                }
            }
            if (!progress && !next.isEmpty()) {
                for (String key : next) {
                    errors.add(key + ": left in Jira because it still has other issues not created on this screen.");
                }
                break;
            }
            pending = next;
        }
        if (deleted.isEmpty() && !errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, String.join(" ", errors));
        }
        log.info(
                "Deleted Jira issues by key projectId={} deleted={} errors={}",
                projectId, deleted.size(), errors.size()
        );
        return new BlinkJiraIssueDeleteResponse(
                deleted.size(),
                0,
                List.copyOf(deleted),
                List.of(),
                List.copyOf(errors)
        );
    }

    public JiraIssueStatusesResponse fetchJiraIssueStatuses(JiraIssueStatusesRequest request) {
        if (request == null || request.issueKeys() == null || request.issueKeys().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Add at least one Jira issue key.");
        }
        Long projectId = requireBlinkProjectId(request.projectId());
        StoredIntegration stored = refreshedJira(projectId);
        String projectKey = jiraProjectKey(stored);
        JiraCallContext ctx = resolveStoredJira(stored);
        List<String> keys = sanitizeIssueKeys(request.issueKeys());
        if (keys.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Add at least one Jira issue key.");
        }

        Map<String, JiraIssueStatusItem> byKey = new LinkedHashMap<>();
        try {
            for (int offset = 0; offset < keys.size(); offset += JIRA_SEARCH_PAGE) {
                List<String> chunk = keys.subList(offset, Math.min(keys.size(), offset + JIRA_SEARCH_PAGE));
                String jql = "project = " + projectKey + " AND key in (" + String.join(", ", chunk) + ")";
                for (JsonNode issue : searchJiraIssues(ctx, jql, List.of("status"))) {
                    JiraIssueStatusItem item = toStatusItem(issue);
                    if (item != null) {
                        byKey.put(item.key().toUpperCase(Locale.ROOT), item);
                    }
                }
            }
        } catch (ApiException ex) {
            if (!jiraSearchUnavailable(ex)) {
                throw ex;
            }
            log.warn("Jira search could not load statuses ({}). Reading each issue instead.", ex.getMessage());
            return statusesByIssue(ctx, projectKey, keys);
        }

        List<JiraIssueStatusItem> ordered = new ArrayList<>();
        for (String key : keys) {
            JiraIssueStatusItem found = byKey.get(key.toUpperCase(Locale.ROOT));
            ordered.add(found != null ? found : new JiraIssueStatusItem(key, null, "missing"));
        }
        return new JiraIssueStatusesResponse(List.copyOf(ordered));
    }

    public JiraIssueStatusItem transitionJiraIssue(JiraIssueTransitionRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Jira transition request is required.");
        }
        String target = required(request.target(), "Choose done or closed.").toLowerCase(Locale.ROOT);
        if (!"done".equals(target) && !"closed".equals(target)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose done or closed.");
        }
        String issueKey = required(request.issueKey(), "Jira issue key is required.");
        if (!JIRA_ISSUE_KEY.matcher(issueKey).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Jira issue key is not valid.");
        }
        Long projectId = requireBlinkProjectId(request.projectId());
        StoredIntegration stored = refreshedJira(projectId);
        String projectKey = jiraProjectKey(stored);
        JiraCallContext ctx = resolveStoredJira(stored);

        JiraIssueStatusItem current = fetchIssueStatus(ctx, projectKey, issueKey);
        if (statusAlreadyMatches(current, target)) {
            return current;
        }

        String transitionsUrl = ctx.apiBase() + "/rest/api/3/issue/" + encode(issueKey) + "/transitions";
        IntegrationHttpGateway.IntegrationHttpResponse listed = http.get(transitionsUrl, ctx.headers());
        if (listed.status() >= 400) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, jiraErrorMessage(listed.body(), listed.status()));
        }
        JsonNode transitionsRoot;
        try {
            transitionsRoot = MAPPER.readTree(listed.body() == null ? "{}" : listed.body());
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not read Jira transitions for " + issueKey + ".");
        }
        String transitionId = pickTransitionId(transitionsRoot, target);
        if (transitionId == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    issueKey + " has no " + target + " step in its Jira workflow."
            );
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("transition").put("id", transitionId);
        String postUrl = ctx.apiBase() + "/rest/api/3/issue/" + encode(issueKey) + "/transitions";
        IntegrationHttpGateway.IntegrationHttpResponse moved = http.post(postUrl, ctx.headers(), body.toString());
        if (moved.status() >= 400 && resolutionRequired(moved.body())) {
            ObjectNode retry = MAPPER.createObjectNode();
            retry.putObject("transition").put("id", transitionId);
            retry.putObject("fields").putObject("resolution").put("name", "Done");
            moved = http.post(postUrl, ctx.headers(), retry.toString());
        }
        if (moved.status() >= 400) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, issueKey + ": " + jiraErrorMessage(moved.body(), moved.status()));
        }
        log.info("Transitioned Jira issue {} to {} projectId={}", issueKey, target, projectId);
        return fetchIssueStatus(ctx, projectKey, issueKey);
    }

    /** Package-visible for tests. Prefer an exact Done or Closed status over a generic done-category step. */
    static String pickTransitionId(JsonNode root, String target) {
        if (root == null || target == null) {
            return null;
        }
        JsonNode transitions = root.path("transitions");
        if (!transitions.isArray()) {
            return null;
        }
        String bestId = null;
        int bestScore = 0;
        for (JsonNode transition : transitions) {
            String id = transition.path("id").asText("").trim();
            if (id.isEmpty()) {
                continue;
            }
            String name = transition.path("name").asText("");
            String toName = transition.path("to").path("name").asText("");
            String category = transition.path("to").path("statusCategory").path("key").asText("");
            int score = transitionScore(target, name, toName, category);
            if (score > bestScore) {
                bestScore = score;
                bestId = id;
            }
        }
        return bestId;
    }

    private static int transitionScore(String target, String name, String toName, String category) {
        String normalizedName = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        String normalizedTo = toName == null ? "" : toName.trim().toLowerCase(Locale.ROOT);
        if ("closed".equals(target)) {
            if ("closed".equals(normalizedTo)) {
                return 100;
            }
            if ("closed".equals(normalizedName)) {
                return 90;
            }
            if (normalizedTo.contains("closed") || normalizedName.contains("closed")) {
                return 80;
            }
            return 0;
        }
        if ("done".equals(normalizedTo)) {
            return 100;
        }
        if ("done".equals(normalizedName)) {
            return 90;
        }
        if ("done".equals(category)) {
            return 70;
        }
        return 0;
    }

    private static boolean statusAlreadyMatches(JiraIssueStatusItem current, String target) {
        if (current == null || current.name() == null) {
            return false;
        }
        return current.name().trim().equalsIgnoreCase(target);
    }

    private static boolean resolutionRequired(String body) {
        if (body == null) {
            return false;
        }
        String message = body.toLowerCase(Locale.ROOT);
        return message.contains("resolution");
    }

    private JiraIssueStatusItem fetchIssueStatus(JiraCallContext ctx, String projectKey, String issueKey) {
        String url = ctx.apiBase() + "/rest/api/3/issue/" + encode(issueKey) + "?fields=status,project";
        IntegrationHttpGateway.IntegrationHttpResponse res = http.get(url, ctx.headers());
        if (res.status() == 404) {
            if (jiraGatewayNotFound(res.body())) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, jiraErrorMessage(res.body(), res.status()));
            }
            throw new ApiException(HttpStatus.NOT_FOUND, issueKey + " was not found in Jira.");
        }
        if (res.status() >= 400) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, jiraErrorMessage(res.body(), res.status()));
        }
        try {
            JsonNode issue = MAPPER.readTree(res.body() == null ? "{}" : res.body());
            String issueProject = trimToNull(issue.path("fields").path("project").path("key").asText(null));
            if (issueProject == null || !issueProject.equalsIgnoreCase(projectKey)) {
                throw new ApiException(HttpStatus.NOT_FOUND, issueKey + " is not in Jira project " + projectKey + ".");
            }
            JiraIssueStatusItem item = toStatusItem(issue);
            if (item == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not read the status of " + issueKey + ".");
            }
            return item;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not read the status of " + issueKey + ".");
        }
    }

    private static JiraIssueStatusItem toStatusItem(JsonNode issue) {
        if (issue == null || issue.isMissingNode() || issue.isNull()) {
            return null;
        }
        String key = trimToNull(issue.path("key").asText(null));
        if (key == null) {
            return null;
        }
        JsonNode status = issue.path("fields").path("status");
        String name = trimToNull(status.path("name").asText(null));
        String rawCategory = status.path("statusCategory").path("key").asText("");
        String category = switch (rawCategory) {
            case "new" -> "todo";
            case "indeterminate" -> "in-progress";
            case "done" -> "done";
            default -> name == null ? "missing" : "unknown";
        };
        return new JiraIssueStatusItem(key, name, category);
    }

    /**
     * Search is the fast path. Jira's OAuth gateway sometimes answers
     * {@code POST /rest/api/3/search/jql} with "404 page not found" while
     * {@code GET /rest/api/3/issue/{key}} still works, which is how tickets are created.
     */
    private JiraIssueStatusesResponse statusesByIssue(JiraCallContext ctx, String projectKey, List<String> keys) {
        try {
            return statusesByIssueOn(ctx, projectKey, keys);
        } catch (ApiException ex) {
            JiraCallContext site = siteJiraContext(ctx);
            if (site == null || !jiraSearchUnavailable(ex)) {
                throw ex;
            }
            log.warn("Jira issue read failed on {}. Retrying {}.", ctx.apiBase(), site.apiBase());
            return statusesByIssueOn(site, projectKey, keys);
        }
    }

    private JiraIssueStatusesResponse statusesByIssueOn(JiraCallContext ctx, String projectKey, List<String> keys) {
        List<JiraIssueStatusItem> ordered = new ArrayList<>();
        ApiException firstFailure = null;
        int loaded = 0;
        for (String key : keys) {
            try {
                ordered.add(fetchIssueStatus(ctx, projectKey, key));
                loaded++;
            } catch (ApiException ex) {
                if (ex.getStatus() == HttpStatus.NOT_FOUND) {
                    ordered.add(new JiraIssueStatusItem(key, null, "missing"));
                    continue;
                }
                if (firstFailure == null) {
                    firstFailure = ex;
                }
                ordered.add(new JiraIssueStatusItem(key, null, "missing"));
            }
        }
        if (loaded == 0 && firstFailure != null) {
            throw firstFailure;
        }
        return new JiraIssueStatusesResponse(List.copyOf(ordered));
    }

    private StoredIntegration refreshedJira(Long projectId) {
        StoredIntegration stored = resolveStoredJira(projectId);
        if (stored == null || trimToNull(stored.projectKey()) == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Connect Jira and select a project for this Blink project first.");
        }
        return stored;
    }

    private static boolean jiraSearchUnavailable(ApiException ex) {
        if (ex == null || ex.getStatus() != HttpStatus.BAD_GATEWAY) {
            return false;
        }
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("404") || message.contains("page not found");
    }

    private static boolean jiraGatewayNotFound(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String text = body.toLowerCase(Locale.ROOT);
        return text.contains("page not found");
    }

    /** Site URL for a bearer token when {@code api.atlassian.com} cannot route the call. */
    private JiraCallContext siteJiraContext(JiraCallContext ctx) {
        if (ctx == null || ctx.browseBase() == null) {
            return null;
        }
        String site = ctx.browseBase().replaceAll("/+$", "");
        String api = ctx.apiBase() == null ? "" : ctx.apiBase().replaceAll("/+$", "");
        if (site.isBlank() || site.equalsIgnoreCase(api)) {
            return null;
        }
        String host = site.replaceFirst("^https?://", "").toLowerCase(Locale.ROOT);
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        if (!host.endsWith(".atlassian.net") || "jira.atlassian.net".equals(host)) {
            return null;
        }
        return new JiraCallContext(site, ctx.headers(), site);
    }

    private String jiraProjectKey(StoredIntegration stored) {
        String projectKey = trimToNull(stored.projectKey());
        if (projectKey == null || !JIRA_PROJECT_KEY.matcher(projectKey).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The connected Jira project key is not valid.");
        }
        return projectKey;
    }

    private static List<String> sanitizeIssueKeys(List<String> rawKeys) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (rawKeys == null) {
            return List.of();
        }
        for (String raw : rawKeys) {
            if (raw == null) {
                continue;
            }
            String key = raw.trim();
            if (JIRA_ISSUE_KEY.matcher(key).matches()) {
                keys.add(key);
            }
        }
        return List.copyOf(keys);
    }

    private static boolean jiraDeleteBlockedByOtherIssues(ApiException ex) {
        if (ex == null) {
            return false;
        }
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("has issues associated")
                || message.contains("has sub-task")
                || message.contains("has subtask")
                || message.contains("you cannot delete")
                || message.contains("cannot be deleted")
                || (message.contains("must delete") && message.contains("child"));
    }

    /** Package-visible for tests — Blink epic marker line. */
    static String blinkSourceEpicId(String plainDescription) {
        if (plainDescription == null || plainDescription.isBlank()) {
            return null;
        }
        Matcher matcher = SOURCE_EPIC_LINE.matcher(plainDescription);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Package-visible for tests — Blink story marker line. */
    static String blinkSourceStoryId(String plainDescription) {
        if (plainDescription == null || plainDescription.isBlank()) {
            return null;
        }
        Matcher matcher = SOURCE_STORY_LINE.matcher(plainDescription);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Long requireBlinkProjectId(String projectIdRaw) {
        Long projectId = parseProjectId(projectIdRaw);
        if (projectId == null || projectId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Blink project id is required.");
        }
        return projectId;
    }

    private StoredIntegration requireStoredJira(Long projectId) {
        StoredIntegration stored = load(projectId, "jira").orElse(null);
        if (stored == null || trimToNull(stored.projectKey()) == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Connect Jira and select a project for this Blink project first.");
        }
        return stored;
    }

    private JiraCallContext resolveStoredJira(StoredIntegration stored) {
        if ("oauth".equalsIgnoreCase(trimToNull(stored.authType()))) {
            return resolveJiraCall(
                    stored.baseUrl(),
                    null,
                    null,
                    stored.cloudId(),
                    stored.accessToken()
            );
        }
        return resolveJiraCall(
                stored.baseUrl(),
                stored.email(),
                stored.accessToken(),
                null,
                null
        );
    }

    private List<BlinkJiraIssueResponse> searchBlinkMarkedIssues(JiraCallContext ctx, String projectKey) {
        String jql = "project = " + projectKey
                + " AND (description ~ \"Source epic:\" OR description ~ \"Source story:\") ORDER BY key ASC";
        List<BlinkJiraIssueResponse> out = new ArrayList<>();
        for (JsonNode issue : searchJiraIssues(
                ctx,
                jql,
                List.of("summary", "description", "issuetype", "project")
        )) {
            BlinkJiraIssueResponse marked = toBlinkMarkedIssue(ctx, projectKey, issue);
            if (marked != null) {
                out.add(marked);
            }
        }
        return out;
    }

    private BlinkJiraIssueResponse fetchBlinkMarkedIssue(JiraCallContext ctx, String projectKey, String issueKey) {
        String url = ctx.apiBase() + "/rest/api/3/issue/" + encode(issueKey)
                + "?fields=summary,description,issuetype,project";
        IntegrationHttpGateway.IntegrationHttpResponse res = http.get(url, ctx.headers());
        if (res.status() == 404) {
            return null;
        }
        if (res.status() >= 400) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, jiraErrorMessage(res.body(), res.status()));
        }
        try {
            return toBlinkMarkedIssue(ctx, projectKey, MAPPER.readTree(res.body() == null ? "{}" : res.body()));
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not parse Jira issue " + issueKey + ".");
        }
    }

    private BlinkJiraIssueResponse toBlinkMarkedIssue(JiraCallContext ctx, String projectKey, JsonNode issue) {
        if (issue == null || issue.isMissingNode() || issue.isNull()) {
            return null;
        }
        String key = trimToNull(issue.path("key").asText(null));
        String issueProject = trimToNull(issue.path("fields").path("project").path("key").asText(null));
        if (key == null || issueProject == null || !issueProject.equalsIgnoreCase(projectKey)) {
            return null;
        }
        String plain = adfToPlainText(issue.path("fields").path("description"));
        String epicId = blinkSourceEpicId(plain);
        String storyId = blinkSourceStoryId(plain);
        String typeName = trimToNull(issue.path("fields").path("issuetype").path("name").asText(null));
        String summary = trimToNull(issue.path("fields").path("summary").asText(null));
        if (summary == null) {
            summary = key;
        }
        String url = ctx.browseBase().replaceAll("/+$", "") + "/browse/" + key;
        if (epicId != null && typeName != null && typeName.equalsIgnoreCase("Epic")) {
            return new BlinkJiraIssueResponse(key, typeName, summary, "epic", epicId, url);
        }
        if (storyId != null) {
            String kindType = typeName == null ? "Story" : typeName;
            return new BlinkJiraIssueResponse(key, kindType, summary, "story", storyId, url);
        }
        // Epic marker without Epic type — still treat as Blink epic for safety listing.
        if (epicId != null) {
            return new BlinkJiraIssueResponse(key, typeName == null ? "Epic" : typeName, summary, "epic", epicId, url);
        }
        return null;
    }

    private List<String> unmarkedChildrenOf(JiraCallContext ctx, String epicKey) {
        List<String> foreign = new ArrayList<>();
        for (JsonNode issue : listChildrenOfEpic(ctx, epicKey)) {
            String key = trimToNull(issue.path("key").asText(null));
            if (key == null) {
                continue;
            }
            String plain = adfToPlainText(issue.path("fields").path("description"));
            if (blinkSourceStoryId(plain) == null && blinkSourceEpicId(plain) == null) {
                foreign.add(key);
            }
        }
        return foreign;
    }

    private List<JsonNode> listChildrenOfEpic(JiraCallContext ctx, String epicKey) {
        String jql = "(parent = " + epicKey + " OR \"Epic Link\" = " + epicKey + ") ORDER BY key ASC";
        return searchJiraIssues(ctx, jql, List.of("summary", "description", "issuetype"));
    }

    /**
     * Atlassian enhanced search ({@code POST /rest/api/3/search/jql}).
     * The legacy {@code /rest/api/3/search} endpoint has been removed.
     */
    private List<JsonNode> searchJiraIssues(JiraCallContext ctx, String jql, List<String> fields) {
        List<JsonNode> out = new ArrayList<>();
        String nextPageToken = null;
        int pages = 0;
        int maxPages = Math.max(1, JIRA_SEARCH_MAX / JIRA_SEARCH_PAGE);
        do {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("jql", jql);
            body.put("maxResults", JIRA_SEARCH_PAGE);
            ArrayNode fieldNodes = body.putArray("fields");
            for (String field : fields) {
                fieldNodes.add(field);
            }
            if (nextPageToken != null && !nextPageToken.isBlank()) {
                body.put("nextPageToken", nextPageToken);
            }
            String url = ctx.apiBase() + "/rest/api/3/search/jql";
            IntegrationHttpGateway.IntegrationHttpResponse res = http.post(url, ctx.headers(), body.toString());
            if (res.status() >= 400) {
                log.warn(
                        "Jira search failed status={} url={} body={}",
                        res.status(), url, abbreviate(res.body(), 180)
                );
                throw new ApiException(HttpStatus.BAD_GATEWAY, jiraErrorMessage(res.body(), res.status()));
            }
            try {
                JsonNode root = MAPPER.readTree(res.body() == null ? "{}" : res.body());
                JsonNode issues = root.path("issues");
                if (issues.isArray()) {
                    for (JsonNode issue : issues) {
                        out.add(issue);
                        if (out.size() >= JIRA_SEARCH_MAX) {
                            return out;
                        }
                    }
                }
                nextPageToken = trimToNull(root.path("nextPageToken").asText(null));
                boolean isLast = root.path("isLast").asBoolean(nextPageToken == null);
                if (isLast || nextPageToken == null || !issues.isArray() || issues.isEmpty()) {
                    break;
                }
            } catch (ApiException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not parse Jira search results.");
            }
            pages++;
        } while (pages < maxPages);
        return out;
    }

    private void deleteJiraIssue(JiraCallContext ctx, String issueKey) {
        deleteJiraIssue(ctx, issueKey, true);
    }

    private void deleteJiraIssue(JiraCallContext ctx, String issueKey, boolean deleteSubtasks) {
        String url = ctx.apiBase() + "/rest/api/3/issue/" + encode(issueKey);
        if (deleteSubtasks) {
            url += "?deleteSubtasks=true";
        }
        IntegrationHttpGateway.IntegrationHttpResponse res = http.delete(url, ctx.headers());
        if (res.status() == 204 || res.status() == 200 || res.status() == 404) {
            return;
        }
        String detail = jiraErrorMessage(res.body(), res.status());
        if (res.status() == 401) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "Jira OAuth session cannot delete " + issueKey + ". Reconnect Atlassian on Integrations, then try again.");
        }
        if (res.status() == 403 || jiraDeletePermissionDenied(detail)) {
            if (Boolean.FALSE.equals(jiraHasPermission(ctx, "DELETE_ISSUES", issueKey))) {
                throw new ApiException(HttpStatus.FORBIDDEN,
                        issueKey + ": your Atlassian account cannot delete issues in this Jira project. Ask a Jira admin to grant the Delete Issues permission, then try again.");
            }
            if (jiraDeletePermissionDenied(detail)) {
                throw new ApiException(HttpStatus.FORBIDDEN,
                        issueKey + ": your Atlassian account cannot delete issues in this Jira project. Ask a Jira admin to grant the Delete Issues permission, then try again.");
            }
            throw new ApiException(HttpStatus.FORBIDDEN, issueKey + ": " + detail);
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, detail);
    }

    private static boolean jiraDeletePermissionDenied(String detail) {
        if (detail == null) {
            return false;
        }
        String message = detail.toLowerCase(Locale.ROOT);
        return message.contains("do not have permission to delete")
                || (message.contains("you do not have permission") && message.contains("delete"))
                || message.contains("delete issues");
    }

    private Boolean jiraHasPermission(JiraCallContext ctx, String permission, String issueKey) {
        String url = ctx.apiBase() + "/rest/api/3/mypermissions?permissions=" + encode(permission);
        if (issueKey != null && !issueKey.isBlank()) {
            url += "&issueKey=" + encode(issueKey);
        }
        IntegrationHttpGateway.IntegrationHttpResponse res = http.get(url, ctx.headers());
        if (res.status() < 200 || res.status() >= 300) {
            return null;
        }
        try {
            JsonNode have = MAPPER.readTree(res.body() == null ? "{}" : res.body())
                    .path("permissions").path(permission).path("havePermission");
            if (have.isMissingNode() || !have.isBoolean()) {
                return null;
            }
            return have.asBoolean();
        } catch (Exception ex) {
            return null;
        }
    }

    public JiraCommentCreateResponse createJiraComment(JiraCommentCreateRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Jira comment request is required.");
        }
        String issueKey = required(request.issueKey(), "Jira issue key is required.");
        String bodyText = required(request.body(), "Comment body is required.");
        String questionId = trimToNull(request.blinkQuestionId());
        if (questionId != null && !bodyText.contains("blink-question:" + questionId)) {
            bodyText = "[blink-question:" + questionId + "]\n" + bodyText;
        }
        // Prefer stored OAuth/token for the Blink project (same path as issue create/list).
        JiraCallContext ctx;
        Long projectId = parseProjectId(request.projectId());
        StoredIntegration stored = projectId == null ? null : load(projectId, "jira").orElse(null);
        if (stored != null) {
            ctx = resolveStoredJira(stored);
        } else {
            ctx = resolveJiraFromRequest(
                    request.projectId(),
                    request.baseUrl(),
                    request.email(),
                    request.token(),
                    request.cloudId(),
                    request.accessToken()
            );
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("body", adfDocument(bodyText));
        String postUrl = ctx.apiBase() + "/rest/api/3/issue/" + encodePathSegment(issueKey) + "/comment";
        IntegrationHttpGateway.IntegrationHttpResponse res = http.post(
                postUrl,
                ctx.headers(),
                writeNode(payload)
        );
        if (res.status() < 200 || res.status() >= 300) {
            log.warn("Jira comment create failed issueKey={} status={} body={}", issueKey, res.status(), abbreviate(res.body(), 400));
            throw new ApiException(HttpStatus.BAD_GATEWAY, jiraErrorMessage(res.body(), res.status()));
        }
        String commentId = firstJsonId(res.body());
        if (commentId == null || commentId.isBlank()) {
            log.warn("Jira comment create returned no id issueKey={} body={}", issueKey, abbreviate(res.body(), 400));
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Jira accepted the request but returned no comment id for " + issueKey + ".");
        }
        // Confirm the comment is actually readable on the issue before telling Blink it posted.
        if (!commentExistsOnIssue(ctx, issueKey, commentId, questionId)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Jira did not persist the clarification comment on " + issueKey + ". Reconnect Jira and try again.");
        }
        log.info("Jira comment created issueKey={} commentId={} questionId={}", issueKey, commentId, questionId);
        return new JiraCommentCreateResponse(
                "ok",
                "Comment posted on " + issueKey,
                issueKey,
                commentId,
                questionId
        );
    }

    public JiraCommentPollResponse pollJiraComments(JiraCommentPollRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return new JiraCommentPollResponse("ok", "Nothing to poll.", List.of());
        }
        JiraCallContext ctx = resolveJiraFromRequest(
                request.projectId(),
                request.baseUrl(),
                request.email(),
                request.token(),
                request.cloudId(),
                request.accessToken()
        );
        String blinkAccountId = resolveBlinkJiraAccountId(ctx);
        List<JiraCommentPollResponse.Reply> replies = new ArrayList<>();
        for (JiraCommentPollRequest.PollItem item : request.items()) {
            if (item == null || trimToNull(item.issueKey()) == null || trimToNull(item.blinkQuestionId()) == null) {
                continue;
            }
            JiraCommentPollResponse.Reply reply = findReplyAfterMarker(
                    ctx,
                    item.issueKey().trim(),
                    item.blinkQuestionId().trim(),
                    blinkAccountId
            );
            if (reply != null) {
                replies.add(reply);
            }
        }
        return new JiraCommentPollResponse(
                "ok",
                replies.isEmpty() ? "No replies yet." : replies.size() + " reply(ies) found.",
                replies
        );
    }

    private JiraCallContext resolveJiraFromRequest(
            String projectId,
            String baseUrl,
            String email,
            String token,
            String cloudId,
            String accessToken
    ) {
        StoredIntegration stored = load(parseProjectId(projectId), "jira").orElse(null);
        return resolveJiraCall(
                firstNonBlank(baseUrl, stored == null ? null : stored.baseUrl()),
                firstNonBlank(email, stored == null ? null : stored.email()),
                firstNonBlank(token, stored != null && !"oauth".equals(stored.authType()) ? stored.accessToken() : null),
                firstNonBlank(cloudId, stored == null ? null : stored.cloudId()),
                firstNonBlank(accessToken, stored != null && "oauth".equals(stored.authType()) ? stored.accessToken() : null)
        );
    }

    private String resolveBlinkJiraAccountId(JiraCallContext ctx) {
        try {
            IntegrationHttpGateway.IntegrationHttpResponse me = getJson(ctx.apiBase() + "/rest/api/3/myself", ctx.headers());
            if (me.status() >= 200 && me.status() < 300) {
                String accountId = firstText(me.body(), "accountId");
                return accountId.isBlank() ? null : accountId;
            }
        } catch (Exception ignored) {
            // Fall through — poll still works without exclusion when myself fails.
        }
        return null;
    }

    /** Prefer issue-key path encoding that keeps PROJ-123 intact (form encoding is wrong for paths). */
    private static String encodePathSegment(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.matches("[A-Za-z][A-Za-z0-9]*-\\d+")) {
            return trimmed;
        }
        return URLEncoder.encode(trimmed, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String firstJsonId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            JsonNode id = node.get("id");
            if (id == null || id.isNull()) {
                return null;
            }
            if (id.isNumber() || id.isTextual()) {
                String text = id.asText("").trim();
                return text.isBlank() ? null : text;
            }
        } catch (Exception ignored) {
            // fall through
        }
        String scanned = scanJsonStringField(body, "id");
        return scanned.isBlank() ? null : scanned;
    }

    private boolean commentExistsOnIssue(
            JiraCallContext ctx,
            String issueKey,
            String commentId,
            String questionId
    ) {
        IntegrationHttpGateway.IntegrationHttpResponse res = http.get(
                ctx.apiBase() + "/rest/api/3/issue/" + encodePathSegment(issueKey) + "/comment/" + encodePathSegment(commentId),
                ctx.headers()
        );
        if (res.status() >= 200 && res.status() < 300) {
            if (questionId == null || questionId.isBlank()) {
                return true;
            }
            String plain = adfToPlainText(readTreeSafe(res.body()).path("body"));
            return plain.contains("blink-question:" + questionId);
        }
        // Fallback: list comments and look for id / marker.
        IntegrationHttpGateway.IntegrationHttpResponse list = http.get(
                ctx.apiBase() + "/rest/api/3/issue/" + encodePathSegment(issueKey) + "/comment",
                ctx.headers()
        );
        if (list.status() < 200 || list.status() >= 300) {
            return false;
        }
        try {
            JsonNode comments = MAPPER.readTree(list.body() == null ? "{}" : list.body()).path("comments");
            if (!comments.isArray()) {
                return false;
            }
            for (JsonNode comment : comments) {
                if (!commentId.equals(comment.path("id").asText(""))) {
                    continue;
                }
                if (questionId == null || questionId.isBlank()) {
                    return true;
                }
                return adfToPlainText(comment.path("body")).contains("blink-question:" + questionId);
            }
        } catch (Exception ex) {
            return false;
        }
        return false;
    }

    private JsonNode readTreeSafe(String body) {
        try {
            return MAPPER.readTree(body == null ? "{}" : body);
        } catch (Exception ex) {
            return MAPPER.createObjectNode();
        }
    }

    private JiraCommentPollResponse.Reply findReplyAfterMarker(
            JiraCallContext ctx,
            String issueKey,
            String questionId,
            String blinkAccountId
    ) {
        IntegrationHttpGateway.IntegrationHttpResponse res = getJson(
                ctx.apiBase() + "/rest/api/3/issue/" + encodePathSegment(issueKey) + "/comment",
                ctx.headers()
        );
        if (res.status() < 200 || res.status() >= 300) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, jiraErrorMessage(res.body(), res.status()));
        }
        try {
            JsonNode root = MAPPER.readTree(res.body() == null ? "{}" : res.body());
            JsonNode comments = root.path("comments");
            if (!comments.isArray()) {
                return null;
            }
            int markerIndex = -1;
            for (int i = 0; i < comments.size(); i++) {
                String plain = adfToPlainText(comments.get(i).path("body"));
                if (plain.contains("blink-question:" + questionId)) {
                    markerIndex = i;
                }
            }
            if (markerIndex < 0) {
                return null;
            }
            for (int i = markerIndex + 1; i < comments.size(); i++) {
                JsonNode comment = comments.get(i);
                String authorId = comment.path("author").path("accountId").asText("");
                if (blinkAccountId != null && blinkAccountId.equals(authorId)) {
                    continue;
                }
                String plain = adfToPlainText(comment.path("body")).trim();
                if (plain.isBlank()) {
                    continue;
                }
                String author = comment.path("author").path("displayName").asText("Unknown");
                String commentId = comment.path("id").asText(null);
                String created = comment.path("created").asText(null);
                return new JiraCommentPollResponse.Reply(questionId, issueKey, commentId, author, plain, created);
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not parse Jira comments for " + issueKey + ".");
        }
        return null;
    }

    /** Package-visible for unit tests. */
    static String adfToPlainText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        StringBuilder sb = new StringBuilder();
        if ("text".equals(node.path("type").asText("")) && node.path("text").isTextual()) {
            sb.append(node.path("text").asText(""));
        }
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode child : content) {
                String part = adfToPlainText(child);
                if (!part.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(part);
                }
            }
        }
        return sb.toString();
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

    private StoredIntegration resolveStoredJira(Long projectId) {
        StoredIntegration stored = load(projectId, "jira").orElse(null);
        if (!hasJiraCredentials(stored)) {
            StoredIntegration latest = integrations.findLatest("jira").orElse(null);
            if (hasJiraCredentials(latest)) {
                if (projectId != null && !projectId.equals(latest.projectId())) {
                    log.info("Reusing Jira credentials from project {} for project {}", latest.projectId(), projectId);
                    persist(
                            projectId,
                            "jira",
                            latest.account(),
                            latest.baseUrl(),
                            latest.email(),
                            latest.username(),
                            latest.organization(),
                            latest.workspace(),
                            latest.projectKey(),
                            latest.projectName(),
                            latest.spaceKey(),
                            latest.cloudId(),
                            storedJiraIsOAuth(latest) ? "oauth" : latest.authType(),
                            latest.accessToken(),
                            latest.refreshToken(),
                            latest.expiresAt()
                    );
                    stored = load(projectId, "jira").orElse(latest);
                } else {
                    stored = latest;
                }
            }
        }
        return refreshJiraAccess(stored, projectId);
    }

    private static boolean hasJiraCredentials(StoredIntegration stored) {
        if (stored == null) {
            return false;
        }
        boolean oauth = stored.cloudId() != null && !stored.cloudId().isBlank()
                && stored.accessToken() != null && !stored.accessToken().isBlank();
        boolean basic = stored.baseUrl() != null && !stored.baseUrl().isBlank()
                && stored.email() != null && !stored.email().isBlank()
                && stored.accessToken() != null && !stored.accessToken().isBlank();
        return oauth || basic;
    }

    private static boolean storedJiraIsOAuth(StoredIntegration stored) {
        if (stored == null) {
            return false;
        }
        if (stored.authType() != null && "oauth".equalsIgnoreCase(stored.authType().trim())) {
            return true;
        }
        return stored.cloudId() != null && !stored.cloudId().isBlank();
    }

    private static boolean jiraAccessExpired(StoredIntegration stored) {
        if (stored == null || stored.expiresAt() == null) {
            return false;
        }
        return !stored.expiresAt().isAfter(Instant.now().plusSeconds(60));
    }

    private StoredIntegration refreshJiraAccess(StoredIntegration stored, Long projectId) {
        if (stored == null) {
            return stored;
        }
        boolean expired = jiraAccessExpired(stored);
        if (stored.refreshToken() == null || stored.refreshToken().isBlank()) {
            if (expired && storedJiraIsOAuth(stored)) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Jira session expired. Reconnect Jira on Integrations.");
            }
            return stored;
        }
        if (!expired) {
            return stored;
        }
        String clientId = properties.getJiraClientId() == null ? "" : properties.getJiraClientId().trim();
        String clientSecret = properties.getJiraClientSecret() == null ? "" : properties.getJiraClientSecret().trim();
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Jira session expired. Reconnect Jira on Integrations.");
        }
        try {
            String tokenJson = writeJson(Map.of(
                    "grant_type", "refresh_token",
                    "client_id", clientId,
                    "client_secret", clientSecret,
                    "refresh_token", stored.refreshToken()
            ));
            IntegrationHttpGateway.IntegrationHttpResponse tokenRes = http.post(
                    "https://auth.atlassian.com/oauth/token",
                    Map.of("Accept", "application/json"),
                    tokenJson,
                    "application/json"
            );
            if (tokenRes.status() == 415) {
                String form = "grant_type=refresh_token"
                        + "&client_id=" + encode(clientId)
                        + "&client_secret=" + encode(clientSecret)
                        + "&refresh_token=" + encode(stored.refreshToken());
                tokenRes = http.post(
                        "https://auth.atlassian.com/oauth/token",
                        Map.of("Accept", "application/json"),
                        form,
                        "application/x-www-form-urlencoded"
                );
            }
            if (tokenRes.status() < 200 || tokenRes.status() >= 300) {
                log.warn("Jira OAuth refresh failed status={} body={}", tokenRes.status(), abbreviate(tokenRes.body(), 240));
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Jira session expired. Reconnect Jira on Integrations.");
            }
            String accessToken = firstText(tokenRes.body(), "access_token");
            if (accessToken.isBlank()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Jira session expired. Reconnect Jira on Integrations.");
            }
            String refreshToken = firstNonBlank(firstText(tokenRes.body(), "refresh_token"), stored.refreshToken());
            Instant expiresAt = Instant.now().plusSeconds(3600);
            try {
                int expiresIn = MAPPER.readTree(tokenRes.body()).path("expires_in").asInt(0);
                if (expiresIn > 0) {
                    expiresAt = Instant.now().plusSeconds(expiresIn);
                }
            } catch (Exception ignored) {
            }
            Long persistId = projectId != null ? projectId : stored.projectId();
            persist(
                    persistId,
                    "jira",
                    stored.account(),
                    stored.baseUrl(),
                    stored.email(),
                    stored.username(),
                    stored.organization(),
                    stored.workspace(),
                    stored.projectKey(),
                    stored.projectName(),
                    stored.spaceKey(),
                    stored.cloudId(),
                    "oauth",
                    accessToken,
                    refreshToken,
                    expiresAt
            );
            return load(persistId, "jira").orElse(stored);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Jira OAuth refresh failed: {}", ex.toString());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Jira session expired. Reconnect Jira on Integrations.");
        }
    }

    private ObjectNode adfDocument(String text) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("type", "doc");
        doc.put("version", 1);
        ArrayNode content = doc.putArray("content");
        String[] lines = text == null ? new String[0] : text.split("\\R");
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                // Jira rejects paragraphs with empty content arrays / empty text nodes.
                continue;
            }
            ObjectNode paragraph = content.addObject();
            paragraph.put("type", "paragraph");
            ObjectNode run = paragraph.putArray("content").addObject();
            run.put("type", "text");
            run.put("text", line);
        }
        if (content.isEmpty()) {
            ObjectNode paragraph = content.addObject();
            paragraph.put("type", "paragraph");
            ObjectNode run = paragraph.putArray("content").addObject();
            run.put("type", "text");
            run.put("text", text == null || text.isBlank() ? " " : text.trim());
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
        if (!fromArray.isBlank()) {
            return fromArray;
        }
        String plain = body == null ? "" : body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (!plain.isBlank() && plain.length() <= 180 && !plain.startsWith("{") && !plain.startsWith("[")) {
            return plain;
        }
        return "Jira returned HTTP " + status + ".";
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
            log.warn("Provider auth failed url={} status={} body={}", url, response.status(), abbreviate(response.body(), 240));
            throw new ApiException(HttpStatus.UNAUTHORIZED, authFailureMessage(url));
        }
        if (response.status() == 404) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Account, organization, project, or space was not found.");
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Provider returned HTTP " + response.status() + ".");
        }
        return response;
    }

    private static String authFailureMessage(String url) {
        String host = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (host.contains("api.figma.com")) {
            return "Figma rejected this token. Use Continue with Figma, or paste a personal access token (starts with figd_) that includes Current user, File content, and File metadata.";
        }
        if (host.contains("api.github.com")) {
            return "Invalid credentials or insufficient permission.";
        }
        if (host.contains("atlassian") || host.contains("/jira") || host.contains("/wiki/")) {
            return "Atlassian rejected these credentials. Reconnect Jira or Confluence on Integrations.";
        }
        if (host.contains("bitbucket")) {
            return "Bitbucket rejected this token. Check the username, workspace, and app password.";
        }
        return "Invalid credentials or insufficient permission.";
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
                if (value != null && !value.isNull() && (value.isTextual() || value.isNumber()) && !value.asText("").isBlank()) {
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
        return FigmaAuth.headers(token);
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

    private static String trimToNull(String value) {
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
