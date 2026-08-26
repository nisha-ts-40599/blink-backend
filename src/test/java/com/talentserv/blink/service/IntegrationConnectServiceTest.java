package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.talentserv.blink.dto.CreateRepositoriesRequest;
import com.talentserv.blink.dto.CreateRepositoriesResponse;
import com.talentserv.blink.dto.IntegrationConnectRequest;
import com.talentserv.blink.dto.IntegrationConnectResponse;
import com.talentserv.blink.error.ApiException;

class IntegrationConnectServiceTest {

    private final Map<String, IntegrationHttpGateway.IntegrationHttpResponse> responses = new HashMap<>();
    private final Map<String, IntegrationHttpGateway.IntegrationHttpResponse> postResponses = new HashMap<>();
    private IntegrationConnectService service;

    @BeforeEach
    void setUp() {
        responses.clear();
        postResponses.clear();
        service = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                IntegrationHttpResponse response = responses.get(url);
                return response == null ? new IntegrationHttpResponse(404, "") : response;
            }

            @Override
            public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
                IntegrationHttpResponse response = postResponses.get(url);
                return response == null ? new IntegrationHttpResponse(404, "") : response;
            }
        });
    }

    @Test
    void githubConnectsWithToken() {
        responses.put("https://api.github.com/user", json(200, "{\"login\":\"octocat\"}"));
        IntegrationConnectResponse result = service.connect(request("github", null, "ghp_test", null, null, null, null, null, null));
        assertThat(result.connected()).isTrue();
        assertThat(result.account()).isEqualTo("octocat");
    }

    @Test
    void jiraRejectsNonAtlassianHost() {
        assertThatThrownBy(() -> service.connect(request(
                "jira", "https://evil.example.com", "token", null, "user@example.com", null, null, null, null
        )))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("atlassian.net");
    }

    @Test
    void jiraConnectsToCloudSite() {
        responses.put("https://acme.atlassian.net/rest/api/3/myself", json(200, "{\"displayName\":\"Ada\"}"));
        IntegrationConnectResponse result = service.connect(request(
                "jira", "https://acme.atlassian.net", "token", null, "ada@acme.com", null, null, null, null
        ));
        assertThat(result.account()).isEqualTo("Ada");
        assertThat(result.detail()).contains("Ada");
    }

    @Test
    void invalidTokenReturnsUnauthorized() {
        responses.put("https://api.github.com/user", json(401, "{\"message\":\"Bad credentials\"}"));
        assertThatThrownBy(() -> service.connect(request("github", null, "bad", null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid credentials");
    }

    @Test
    void githubCreatesUserRepositories() {
        responses.put("https://api.github.com/user", json(200, "{\"login\":\"octocat\"}"));
        postResponses.put("https://api.github.com/user/repos", json(201, "{\"html_url\":\"https://github.com/octocat/customer-service\"}"));
        CreateRepositoriesResponse result = service.createRepositories(new CreateRepositoriesRequest(
                "github",
                "ghp_test",
                null,
                null,
                null,
                List.of(new CreateRepositoriesRequest.RepoSpec("customer-service", "Customer domain"))
        ));
        assertThat(result.repositories()).hasSize(1);
        assertThat(result.repositories().get(0).status()).isEqualTo("created");
        assertThat(result.repositories().get(0).htmlUrl()).isEqualTo("https://github.com/octocat/customer-service");
    }

    @Test
    void githubReportsExistingRepository() {
        responses.put("https://api.github.com/user", json(200, "{\"login\":\"octocat\"}"));
        postResponses.put("https://api.github.com/user/repos", json(422, "{\"message\":\"Repository creation failed.\",\"errors\":[{\"code\":\"already_exists\"}]}"));
        CreateRepositoriesResponse result = service.createRepositories(new CreateRepositoriesRequest(
                "github",
                "ghp_test",
                null,
                null,
                null,
                List.of(new CreateRepositoriesRequest.RepoSpec("customer-service", "Customer domain"))
        ));
        assertThat(result.repositories().get(0).status()).isEqualTo("exists");
    }

    @Test
    void createRepositoriesRejectsNonGithub() {
        assertThatThrownBy(() -> service.createRepositories(new CreateRepositoriesRequest(
                "bitbucket",
                "token",
                "riya",
                null,
                "ws",
                List.of(new CreateRepositoriesRequest.RepoSpec("svc", "svc"))
        )))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("GitHub");
    }

    @Test
    void bitbucketRequiresWorkspace() {
        assertThatThrownBy(() -> service.connect(request(
                "bitbucket", null, "app-pass", "riya", null, null, "", null, null
        )))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("workspace");
    }

    private static IntegrationHttpGateway.IntegrationHttpResponse json(int status, String body) {
        return new IntegrationHttpGateway.IntegrationHttpResponse(status, body);
    }

    private static IntegrationConnectRequest request(
            String provider,
            String baseUrl,
            String token,
            String username,
            String email,
            String organization,
            String workspace,
            String projectKey,
            String spaceKey
    ) {
        return new IntegrationConnectRequest(
                provider, baseUrl, token, username, email, organization, workspace, projectKey, spaceKey
        );
    }
}
