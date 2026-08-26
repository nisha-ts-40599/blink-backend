package com.talentserv.blink.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.talentserv.blink.dto.CreateRepositoriesRequest;
import com.talentserv.blink.dto.CreateRepositoriesResponse;
import com.talentserv.blink.dto.IntegrationConnectRequest;
import com.talentserv.blink.dto.IntegrationConnectResponse;
import com.talentserv.blink.error.ApiException;

@Service
public class IntegrationConnectService {

    private final IntegrationHttpGateway http;

    public IntegrationConnectService(IntegrationHttpGateway http) {
        this.http = http;
    }

    public IntegrationConnectResponse connect(IntegrationConnectRequest request) {
        String provider = request.provider().trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "github" -> connectGitHub(request);
            case "bitbucket" -> connectBitbucket(request);
            case "jira" -> connectJira(request);
            case "confluence" -> connectConfluence(request);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported provider.");
        };
    }

    public CreateRepositoriesResponse createRepositories(CreateRepositoriesRequest request) {
        String provider = request.provider().trim().toLowerCase(Locale.ROOT);
        if (!"github".equals(provider)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only GitHub repository creation is supported.");
        }
        String token = required(request.token(), "GitHub personal access token is required.");
        Map<String, String> headers = githubHeaders(token);
        getJson("https://api.github.com/user", headers);

        String org = trimToNull(request.organization());
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
        IntegrationHttpGateway.IntegrationHttpResponse user = getJson(
                "https://api.github.com/user",
                githubHeaders(token)
        );
        String account = firstText(user.body(), "login", "name");
        String org = trimToNull(request.organization());
        if (org != null) {
            getJson(
                    "https://api.github.com/orgs/" + encode(org),
                    githubHeaders(token)
            );
            return new IntegrationConnectResponse(true, "github", account, "Connected as " + account + " to " + org);
        }
        return new IntegrationConnectResponse(true, "github", account, "Connected as " + account);
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
        if (projectKey != null) {
            getJson("https://" + host + "/rest/api/3/project/" + encode(projectKey), headers);
            return new IntegrationConnectResponse(true, "jira", account, "Connected as " + account + " to project " + projectKey);
        }
        return new IntegrationConnectResponse(true, "jira", account, "Connected as " + account);
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

    private String firstText(String body, String... fields) {
        String source = body == null ? "" : body;
        for (String field : fields) {
            Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                    .matcher(source);
            if (matcher.find()) {
                String value = matcher.group(1).replace("\\\"", "\"");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "Connected account";
    }

    private static Map<String, String> githubHeaders(String token) {
        return Map.of(
                "Authorization", "Bearer " + token,
                "X-GitHub-Api-Version", "2022-11-28",
                "Accept", "application/vnd.github+json"
        );
    }

    private static String repoJson(String name, String description) {
        String desc = description == null ? "" : description;
        return "{\"name\":" + jsonString(name)
                + ",\"description\":" + jsonString(desc)
                + ",\"private\":true,\"auto_init\":false}";
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean containsIgnoreCase(String body, String needle) {
        return body != null && body.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String githubErrorMessage(String body, int status) {
        if (body != null && !body.isBlank()) {
            Matcher matcher = Pattern.compile("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(body);
            if (matcher.find()) {
                String value = matcher.group(1).replace("\\\"", "\"");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "GitHub returned HTTP " + status + ".";
    }

    static String atlassianHost(String raw) {
        String value = required(raw, "Atlassian site URL is required.");
        if (!value.contains("://")) {
            value = "https://" + value;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter a valid https://your-site.atlassian.net URL.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Atlassian site URL must use https.");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.endsWith(".atlassian.net")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only *.atlassian.net Cloud sites are supported.");
        }
        return host;
    }

    private static String basic(String user, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
