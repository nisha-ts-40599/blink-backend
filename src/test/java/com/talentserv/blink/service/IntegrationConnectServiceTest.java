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
        assertThat(result.token()).isNull();
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
                null,
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
                null,
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
                null,
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

    @Test
    void jiraOAuthUrlNotConfiguredByDefault() {
        var urlResponse = service.getJiraOAuthUrl();
        assertThat(urlResponse.configured()).isFalse();
        assertThat(urlResponse.url()).isNull();
    }

    @Test
    void jiraOAuthUrlConfiguredWhenClientIdPresent() {
        var props = new com.talentserv.blink.config.BlinkProperties();
        props.setJiraClientId("my-atlassian-client-id");
        props.setJiraClientSecret("my-secret");
        var oauthService = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                return new IntegrationHttpResponse(200, "{}");
            }
        }, props);

        var urlResponse = oauthService.getJiraOAuthUrl();
        assertThat(urlResponse.configured()).isTrue();
        assertThat(urlResponse.url()).contains("https://auth.atlassian.com/authorize");
        assertThat(urlResponse.url()).contains("client_id=my-atlassian-client-id");
        assertThat(urlResponse.url()).contains("response_type=code");
    }

    @Test
    void githubOAuthUrlUnconfiguredWithoutClientId() {
        var urlResponse = service.getGithubOAuthUrl();
        assertThat(urlResponse.configured()).isFalse();
        assertThat(urlResponse.url()).isNull();
    }

    @Test
    void githubOAuthUrlConfiguredWhenClientIdPresent() {
        var props = new com.talentserv.blink.config.BlinkProperties();
        props.setGithubClientId("Ov23liTestClient");
        props.setGithubClientSecret("github-secret");
        var oauthService = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                return new IntegrationHttpResponse(200, "{}");
            }
        }, props);

        var urlResponse = oauthService.getGithubOAuthUrl(
                "https://blink-backend-af7x.onrender.com/api/integrations/github/oauth/callback",
                "https://blink-backend-af7x.onrender.com"
        );
        assertThat(urlResponse.configured()).isTrue();
        assertThat(urlResponse.url()).contains("https://github.com/login/oauth/authorize");
        assertThat(urlResponse.url()).contains("client_id=Ov23liTestClient");
        assertThat(urlResponse.url()).contains("scope=");
        assertThat(urlResponse.redirectUri())
                .isEqualTo("https://blink-backend-af7x.onrender.com/api/integrations/github/oauth/callback");
        assertThat(urlResponse.url()).contains("blink-backend-af7x.onrender.com");
        assertThat(urlResponse.url()).doesNotContain("localhost");
    }

    @Test
    void githubOAuthExchangeSuccess() {
        var props = new com.talentserv.blink.config.BlinkProperties();
        props.setGithubClientId("github-client");
        props.setGithubClientSecret("github-secret");
        props.setGithubRedirectUri("http://localhost:5173/api/integrations/github/oauth/callback");
        MemoryProjectIntegrationStore store = new MemoryProjectIntegrationStore();
        var oauthService = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                if (url.contains("api.github.com/user/orgs")) {
                    return json(200, "[{\"login\":\"acme\",\"avatar_url\":\"https://example/acme.png\"}]");
                }
                if (url.contains("api.github.com/user")) {
                    return json(200, "{\"login\":\"octocat\",\"name\":\"The Octocat\"}");
                }
                if (url.contains("api.github.com/orgs/acme")) {
                    return json(200, "{\"login\":\"acme\"}");
                }
                return new IntegrationHttpResponse(404, "");
            }

            @Override
            public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
                if (url.contains("github.com/login/oauth/access_token")) {
                    return json(200, "{\"access_token\":\"gho_oauth_token\",\"token_type\":\"bearer\",\"scope\":\"repo,read:org\"}");
                }
                return new IntegrationHttpResponse(404, "");
            }
        }, props, store);

        var exchangeRes = oauthService.exchangeGithubOAuth(
                new com.talentserv.blink.dto.GithubOAuthExchangeRequest("auth_code_gh", null, "42", "acme")
        );

        assertThat(exchangeRes.connected()).isTrue();
        assertThat(exchangeRes.account()).isEqualTo("octocat");
        assertThat(exchangeRes.authType()).isEqualTo("oauth");
        assertThat(exchangeRes.token()).isNull();
        assertThat(exchangeRes.detail()).contains("acme");
        assertThat(store.find(42L, "github").orElseThrow().accessToken()).isEqualTo("gho_oauth_token");
        assertThat(store.find(42L, "github").orElseThrow().authType()).isEqualTo("oauth");
        assertThat(store.find(42L, "github").orElseThrow().organization()).isEqualTo("acme");
        assertThat(exchangeRes.organization()).isEqualTo("acme");
        assertThat(exchangeRes.organizations()).extracting(com.talentserv.blink.dto.GithubOrgDto::login)
                .containsExactly("octocat", "acme");
    }

    @Test
    void githubListsPersonalAccountAndOrganizations() {
        responses.put("https://api.github.com/user", json(200, "{\"login\":\"octocat\",\"avatar_url\":\"https://example/octo.png\"}"));
        responses.put("https://api.github.com/user/orgs?per_page=100", json(200, "[{\"login\":\"acme\"},{\"login\":\"talent\"}]"));
        MemoryProjectIntegrationStore store = new MemoryProjectIntegrationStore();
        store.upsert(new com.talentserv.blink.dto.StoredIntegration(
                42L, "github", "octocat", "https://github.com", null, "octocat", null, null,
                null, null, null, null, "oauth", "gho_oauth_token", null, null
        ));
        IntegrationConnectService storedService = new IntegrationConnectService(
                serviceGateway(),
                new com.talentserv.blink.config.BlinkProperties(),
                store
        );

        var orgs = storedService.fetchGithubOrgs(new com.talentserv.blink.dto.GithubOrgsRequest("42", null));

        assertThat(orgs).extracting(com.talentserv.blink.dto.GithubOrgDto::login).containsExactly("octocat", "acme", "talent");
        assertThat(orgs.get(0).personal()).isTrue();
        assertThat(orgs.get(1).personal()).isFalse();
    }

    @Test
    void githubOAuthExchangeRejectsMissingAccessToken() {
        var props = new com.talentserv.blink.config.BlinkProperties();
        props.setGithubClientId("github-client");
        props.setGithubClientSecret("github-secret");
        var oauthService = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                return new IntegrationHttpResponse(404, "");
            }

            @Override
            public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
                return json(200, "{\"error\":\"bad_verification_code\",\"error_description\":\"The code passed is incorrect or expired.\"}");
            }
        }, props);

        assertThatThrownBy(() -> oauthService.exchangeGithubOAuth(
                new com.talentserv.blink.dto.GithubOAuthExchangeRequest("bad_code", null, null, null)
        ))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("incorrect or expired");
    }

    @Test
    void figmaOAuthUrlUnconfiguredWithoutClientId() {
        var urlResponse = service.getFigmaOAuthUrl();
        assertThat(urlResponse.configured()).isFalse();
        assertThat(urlResponse.url()).isNull();
    }

    @Test
    void figmaOAuthUrlConfiguredWhenClientIdPresent() {
        var props = new com.talentserv.blink.config.BlinkProperties();
        props.setFigmaClientId("figma-client");
        props.setFigmaClientSecret("figma-secret");
        var oauthService = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                return new IntegrationHttpResponse(200, "{}");
            }
        }, props);

        var urlResponse = oauthService.getFigmaOAuthUrl(
                "https://blink-backend-af7x.onrender.com/api/integrations/figma/oauth/callback",
                "https://blink-backend-af7x.onrender.com"
        );
        assertThat(urlResponse.configured()).isTrue();
        assertThat(urlResponse.url()).contains("https://www.figma.com/oauth");
        assertThat(urlResponse.url()).contains("client_id=figma-client");
        assertThat(urlResponse.url()).contains("response_type=code");
        assertThat(urlResponse.url()).contains("current_user%3Aread");
        assertThat(urlResponse.url()).contains("file_content%3Aread");
        assertThat(urlResponse.url()).contains("file_metadata%3Aread");
        assertThat(urlResponse.url()).doesNotContain("folders");
        assertThat(urlResponse.url()).doesNotContain("selections");
        assertThat(urlResponse.url()).doesNotContain("projects");
        assertThat(urlResponse.url()).doesNotContain("files%3Aread");
        assertThat(urlResponse.redirectUri())
                .isEqualTo("https://blink-backend-af7x.onrender.com/api/integrations/figma/oauth/callback");
        assertThat(urlResponse.url()).doesNotContain("localhost");
    }

    @Test
    void figmaOAuthExchangeSuccessListsTeamsAndProjects() {
        var props = new com.talentserv.blink.config.BlinkProperties();
        props.setFigmaClientId("figma-client");
        props.setFigmaClientSecret("figma-secret");
        props.setFigmaRedirectUri("http://localhost:5173/api/integrations/figma/oauth/callback");
        MemoryProjectIntegrationStore store = new MemoryProjectIntegrationStore();
        var oauthService = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                if (url.contains("api.figma.com/v1/me")) {
                    return json(200, "{\"id\":\"u1\",\"handle\":\"ada\",\"email\":\"ada@acme.com\",\"teams\":[{\"id\":\"111\",\"name\":\"Acme Design\"}]}");
                }
                if (url.contains("api.figma.com/v1/teams/111/projects")) {
                    return json(200, "{\"projects\":[{\"id\":\"222\",\"name\":\"Mobile App\"}]}");
                }
                return new IntegrationHttpResponse(404, "");
            }

            @Override
            public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
                if (url.contains("api.figma.com/v1/oauth/token")) {
                    return json(200, "{\"access_token\":\"figma_oauth_token\",\"refresh_token\":\"figma_refresh\",\"expires_in\":7776000,\"user_id_string\":\"u1\"}");
                }
                return new IntegrationHttpResponse(404, "");
            }
        }, props, store);

        var exchangeRes = oauthService.exchangeFigmaOAuth(
                new com.talentserv.blink.dto.FigmaOAuthExchangeRequest("auth_code_figma", null, "42", "111")
        );

        assertThat(exchangeRes.connected()).isTrue();
        assertThat(exchangeRes.account()).isEqualTo("ada");
        assertThat(exchangeRes.authType()).isEqualTo("oauth");
        assertThat(exchangeRes.token()).isNull();
        assertThat(exchangeRes.organization()).isEqualTo("111");
        assertThat(exchangeRes.projectKey()).isEqualTo("222");
        assertThat(exchangeRes.projectName()).isEqualTo("Mobile App");
        assertThat(exchangeRes.organizations()).extracting(com.talentserv.blink.dto.GithubOrgDto::login)
                .containsExactly("111");
        assertThat(store.find(42L, "figma").orElseThrow().accessToken()).isEqualTo("figma_oauth_token");
        assertThat(store.find(42L, "figma").orElseThrow().organization()).isEqualTo("111");
    }

    @Test
    void figmaParsesTeamIdFromUrl() {
        responses.put("https://api.figma.com/v1/me", json(200, "{\"handle\":\"ada\"}"));
        responses.put("https://api.figma.com/v1/teams/987654321/projects", json(200, "{\"projects\":[]}"));
        var result = service.connect(new IntegrationConnectRequest(
                "figma",
                "42",
                null,
                "figd_token",
                null,
                null,
                "https://www.figma.com/files/team/987654321/Acme-Design",
                null,
                null,
                null
        ));
        assertThat(result.connected()).isTrue();
        assertThat(result.organization()).isEqualTo("987654321");
        assertThat(result.detail()).contains("987654321");
    }

    @Test
    void jiraOAuthExchangeSuccess() {
        var props = new com.talentserv.blink.config.BlinkProperties();
        props.setJiraClientId("my-client-id");
        props.setJiraClientSecret("my-secret");
        props.setJiraRedirectUri("http://localhost:5173/integrations/jira/callback");

        postResponses.put(
                "https://auth.atlassian.com/oauth/token",
                json(200, "{\"access_token\":\"atlassian_access_token_123\",\"token_type\":\"Bearer\"}")
        );
        responses.put(
                "https://api.atlassian.com/oauth/token/accessible-resources",
                json(200, "[{\"id\":\"cloud-id-123\",\"name\":\"Acme Corp\",\"url\":\"https://acme.atlassian.net\"}]")
        );
        responses.put(
                "https://api.atlassian.com/ex/jira/cloud-id-123/rest/api/3/myself",
                json(200, "{\"displayName\":\"Ada Lovelace\",\"emailAddress\":\"ada@acme.com\"}")
        );
        responses.put(
                "https://api.atlassian.com/ex/jira/cloud-id-123/rest/api/3/project",
                json(200, "[{\"id\":\"1001\",\"key\":\"FIT\",\"name\":\"Fitoyo Mobile\"}]")
        );

        var oauthService = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                var res = responses.get(url);
                return res == null ? new IntegrationHttpResponse(404, "") : res;
            }

            @Override
            public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
                var res = postResponses.get(url);
                return res == null ? new IntegrationHttpResponse(404, "") : res;
            }
        }, props);

        var exchangeRes = oauthService.exchangeJiraOAuth(
                new com.talentserv.blink.dto.JiraOAuthExchangeRequest("auth_code_xyz", null, null)
        );

        assertThat(exchangeRes.connected()).isTrue();
        assertThat(exchangeRes.account()).isEqualTo("Ada Lovelace");
        assertThat(exchangeRes.cloudId()).isEqualTo("cloud-id-123");
        assertThat(exchangeRes.projectKey()).isEqualTo("FIT");
        assertThat(exchangeRes.projectName()).isEqualTo("Fitoyo Mobile");
        assertThat(exchangeRes.projects()).hasSize(1);
        assertThat(exchangeRes.projects().get(0).key()).isEqualTo("FIT");
        assertThat(exchangeRes.token()).isNull();
    }

    @Test
    void jiraFetchProjectsDirect() {
        responses.put(
                "https://acme.atlassian.net/rest/api/3/project",
                json(200, "[{\"id\":\"101\",\"key\":\"CORE\",\"name\":\"Core Platform\"},{\"id\":\"102\",\"key\":\"UI\",\"name\":\"Web Frontend\"}]")
        );
        var projects = service.fetchJiraProjects(
                new com.talentserv.blink.dto.JiraProjectsRequest(null, "https://acme.atlassian.net", "ada@acme.com", "token", null, null)
        );
        assertThat(projects).hasSize(2);
        assertThat(projects.get(0).key()).isEqualTo("CORE");
        assertThat(projects.get(0).name()).isEqualTo("Core Platform");
        assertThat(projects.get(1).key()).isEqualTo("UI");
    }

    @Test
    void jiraOAuthExchangeRetriesFormWhenJsonReturns415() {
        var props = new com.talentserv.blink.config.BlinkProperties();
        props.setJiraClientId("my-client-id");
        props.setJiraClientSecret("my-secret");
        props.setJiraRedirectUri("http://localhost:5173/api/integrations/jira/oauth/callback");
        java.util.concurrent.atomic.AtomicInteger posts = new java.util.concurrent.atomic.AtomicInteger();
        var oauthService = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                if (url.contains("accessible-resources")) {
                    return json(200, "[{\"id\":\"cloud-id-123\",\"name\":\"Acme Corp\",\"url\":\"https://acme.atlassian.net\"}]");
                }
                if (url.contains("/myself")) {
                    return json(200, "{\"displayName\":\"Ada Lovelace\"}");
                }
                if (url.contains("/project")) {
                    return json(200, "[{\"id\":\"1001\",\"key\":\"FIT\",\"name\":\"Fitoyo Mobile\"}]");
                }
                return new IntegrationHttpResponse(404, "");
            }

            @Override
            public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
                int n = posts.incrementAndGet();
                if (n == 1) {
                    return json(415, "{\"error\":\"unsupported_media_type\",\"error_description\":\"Unsupported Media Type\"}");
                }
                return json(200, "{\"access_token\":\"atlassian_access_token_123\",\"token_type\":\"Bearer\"}");
            }
        }, props);

        var exchangeRes = oauthService.exchangeJiraOAuth(
                new com.talentserv.blink.dto.JiraOAuthExchangeRequest("auth_code_xyz", null, null)
        );
        assertThat(posts.get()).isEqualTo(2);
        assertThat(exchangeRes.connected()).isTrue();
        assertThat(exchangeRes.account()).isEqualTo("Ada Lovelace");
    }

    @Test
    void jiraOAuthExchangeHtmlErrorDoesNotStackOverflow() {
        var props = new com.talentserv.blink.config.BlinkProperties();
        props.setJiraClientId("my-client-id");
        props.setJiraClientSecret("my-secret");
        String html = "<!DOCTYPE html><html><head>" + "\"".repeat(20_000)
                + "</head><body>Unsupported Media Type</body></html>";
        var oauthService = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                return new IntegrationHttpResponse(404, "");
            }

            @Override
            public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
                return new IntegrationHttpResponse(415, html);
            }
        }, props);

        assertThatThrownBy(() -> oauthService.exchangeJiraOAuth(
                new com.talentserv.blink.dto.JiraOAuthExchangeRequest("auth_code_xyz", null, null)
        ))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unsupported Media Type");
    }

    @Test
    void jiraCreatesStoriesUnderEpicInCompanyManagedProject() {
        responses.put(
                "https://acme.atlassian.net/rest/api/3/issuetype",
                json(200, "[{\"name\":\"Epic\"},{\"name\":\"Story\"}]")
        );
        responses.put(
                "https://acme.atlassian.net/rest/api/3/field",
                json(200, "[{\"id\":\"customfield_10011\",\"name\":\"Epic Name\",\"schema\":{\"custom\":\"com.pyxis.greenhopper.jira:gh-epic-label\"}},{\"id\":\"customfield_10014\",\"name\":\"Epic Link\",\"schema\":{\"custom\":\"com.pyxis.greenhopper.jira:gh-epic-link\"}}]")
        );
        responses.put(
                "https://acme.atlassian.net/rest/api/3/project/FIT",
                json(200, "{\"key\":\"FIT\",\"simplified\":false,\"style\":\"classic\"}")
        );
        java.util.List<String[]> posts = new java.util.ArrayList<>();
        var creating = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                var res = responses.get(url);
                return res == null ? new IntegrationHttpResponse(404, "") : res;
            }

            @Override
            public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
                posts.add(new String[] {url, jsonBody});
                if (url.endsWith("/rest/api/3/issue") && jsonBody.contains("\"Epic\"")) {
                    return json(201, "{\"key\":\"FIT-1\"}");
                }
                if (url.endsWith("/rest/api/3/issue")) {
                    return json(201, "{\"key\":\"FIT-2\"}");
                }
                if (url.contains("/rest/agile/1.0/epic/FIT-1/issue")) {
                    return json(204, "");
                }
                return json(404, "");
            }
        });

        var result = creating.createJiraIssues(new com.talentserv.blink.dto.JiraCreateIssuesRequest(
                null,
                "https://acme.atlassian.net",
                "ada@acme.com",
                "token",
                null,
                null,
                "FIT",
                List.of(new com.talentserv.blink.dto.JiraEpicSpec("E1", "Core Platform", "Deliver core capabilities", List.of("S1"))),
                List.of(new com.talentserv.blink.dto.JiraStorySpec(
                        "S1", null, "User login", null, "user", "to sign in", "I can access the app", List.of("Given login page")
                ))
        ));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.issues()).hasSize(2);
        assertThat(result.issues().get(0).jiraKey()).isEqualTo("FIT-1");
        assertThat(result.issues().get(1).jiraKey()).isEqualTo("FIT-2");
        assertThat(result.issues().get(1).jiraUrl()).contains("/browse/FIT-2");

        String storyBody = posts.stream()
                .filter(item -> item[0].endsWith("/rest/api/3/issue") && item[1].contains("User login"))
                .map(item -> item[1])
                .findFirst()
                .orElseThrow();
        assertThat(storyBody).contains("\"customfield_10014\":\"FIT-1\"");
        assertThat(storyBody).doesNotContain("\"parent\"");

        assertThat(posts.stream().anyMatch(item ->
                item[0].contains("/rest/agile/1.0/epic/FIT-1/issue") && item[1].contains("FIT-2")
        )).isTrue();
    }

    @Test
    void jiraCreatesStoriesUnderEpicInTeamManagedProject() {
        responses.put(
                "https://acme.atlassian.net/rest/api/3/issuetype",
                json(200, "[{\"name\":\"Epic\"},{\"name\":\"Story\"}]")
        );
        responses.put(
                "https://acme.atlassian.net/rest/api/3/field",
                json(200, "[]")
        );
        responses.put(
                "https://acme.atlassian.net/rest/api/3/project/FIT",
                json(200, "{\"key\":\"FIT\",\"simplified\":true,\"style\":\"next-gen\"}")
        );
        java.util.List<String[]> posts = new java.util.ArrayList<>();
        var creating = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                var res = responses.get(url);
                return res == null ? new IntegrationHttpResponse(404, "") : res;
            }

            @Override
            public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
                posts.add(new String[] {url, jsonBody});
                if (url.endsWith("/rest/api/3/issue") && jsonBody.contains("\"Epic\"")) {
                    return json(201, "{\"key\":\"FIT-1\"}");
                }
                if (url.endsWith("/rest/api/3/issue")) {
                    return json(201, "{\"key\":\"FIT-2\"}");
                }
                if (url.contains("/rest/agile/1.0/epic/FIT-1/issue")) {
                    return json(204, "");
                }
                return json(404, "");
            }
        });

        var result = creating.createJiraIssues(new com.talentserv.blink.dto.JiraCreateIssuesRequest(
                null,
                "https://acme.atlassian.net",
                "ada@acme.com",
                "token",
                null,
                null,
                "FIT",
                List.of(new com.talentserv.blink.dto.JiraEpicSpec("E1", "Core Platform", "Deliver core capabilities")),
                List.of(new com.talentserv.blink.dto.JiraStorySpec(
                        "S1", "E1", "User login", null, "user", "to sign in", "I can access the app", List.of("Given login page")
                ))
        ));

        assertThat(result.status()).isEqualTo("ok");
        String storyBody = posts.stream()
                .filter(item -> item[0].endsWith("/rest/api/3/issue") && item[1].contains("User login"))
                .map(item -> item[1])
                .findFirst()
                .orElseThrow();
        assertThat(storyBody).contains("\"parent\":{\"key\":\"FIT-1\"}");
        assertThat(storyBody).doesNotContain("customfield_10014");
    }

    @Test
    void githubCreateUsesStoredTokenWhenRequestOmitsIt() {
        responses.put("https://api.github.com/user", json(200, "{\"login\":\"octocat\"}"));
        postResponses.put("https://api.github.com/user/repos", json(201, "{\"html_url\":\"https://github.com/octocat/customer-service\"}"));
        MemoryProjectIntegrationStore store = new MemoryProjectIntegrationStore();
        IntegrationConnectService storedService = new IntegrationConnectService(
                serviceGateway(),
                new com.talentserv.blink.config.BlinkProperties(),
                store
        );
        storedService.connect(new IntegrationConnectRequest(
                "github", "42", null, "ghp_stored", null, null, null, null, null, null
        ));
        assertThat(store.find(42L, "github")).isPresent();
        assertThat(store.find(42L, "github").orElseThrow().accessToken()).isEqualTo("ghp_stored");

        CreateRepositoriesResponse result = storedService.createRepositories(new CreateRepositoriesRequest(
                "github",
                "42",
                null,
                null,
                null,
                null,
                List.of(new CreateRepositoriesRequest.RepoSpec("customer-service", "Customer domain"))
        ));
        assertThat(result.repositories().get(0).status()).isEqualTo("created");
    }

    @Test
    void createJiraCommentPostsAdfWithBlinkMarker() {
        List<String[]> posts = new java.util.ArrayList<>();
        var commenting = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                return json(404, "");
            }

            @Override
            public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
                posts.add(new String[] {url, jsonBody});
                if (url.contains("/rest/api/3/issue/FIT-1/comment")) {
                    return json(201, "{\"id\":\"10001\"}");
                }
                return json(404, "");
            }
        });

        var result = commenting.createJiraComment(new com.talentserv.blink.dto.JiraCommentCreateRequest(
                null,
                "https://acme.atlassian.net",
                "ada@acme.com",
                "token",
                null,
                null,
                "FIT-1",
                "Please confirm MFA choice.",
                "q-42"
        ));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.commentId()).isEqualTo("10001");
        assertThat(result.blinkQuestionId()).isEqualTo("q-42");
        assertThat(posts).hasSize(1);
        assertThat(posts.get(0)[1]).contains("blink-question:q-42");
        assertThat(posts.get(0)[1]).contains("Please confirm MFA choice.");
    }

    @Test
    void pollJiraCommentsReturnsNextReplyAfterMarkerExcludingBlinkAccount() {
        String commentsJson = """
                {
                  "comments": [
                    {
                      "id": "1",
                      "created": "2026-01-01T00:00:00.000+0000",
                      "author": {"accountId": "blink-bot", "displayName": "Blink"},
                      "body": {"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"<!-- blink-question:q-9 -->"}]}]}
                    },
                    {
                      "id": "2",
                      "created": "2026-01-01T01:00:00.000+0000",
                      "author": {"accountId": "blink-bot", "displayName": "Blink"},
                      "body": {"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"Follow-up from Blink"}]}]}
                    },
                    {
                      "id": "3",
                      "created": "2026-01-01T02:00:00.000+0000",
                      "author": {"accountId": "person-1", "displayName": "Ada"},
                      "body": {"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"Use TOTP for MFA."}]}]}
                    }
                  ]
                }
                """;
        var polling = new IntegrationConnectService(new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                if (url.endsWith("/rest/api/3/myself")) {
                    return json(200, "{\"accountId\":\"blink-bot\",\"displayName\":\"Blink\"}");
                }
                if (url.contains("/rest/api/3/issue/FIT-9/comment")) {
                    return json(200, commentsJson);
                }
                return json(404, "");
            }

            @Override
            public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
                return json(404, "");
            }
        });

        var result = polling.pollJiraComments(new com.talentserv.blink.dto.JiraCommentPollRequest(
                null,
                "https://acme.atlassian.net",
                "ada@acme.com",
                "token",
                null,
                null,
                List.of(new com.talentserv.blink.dto.JiraCommentPollRequest.PollItem("FIT-9", "q-9"))
        ));

        assertThat(result.replies()).hasSize(1);
        assertThat(result.replies().get(0).blinkQuestionId()).isEqualTo("q-9");
        assertThat(result.replies().get(0).author()).isEqualTo("Ada");
        assertThat(result.replies().get(0).body()).contains("Use TOTP for MFA.");
        assertThat(result.replies().get(0).commentId()).isEqualTo("3");
    }

    @Test
    void adfToPlainTextFlattensParagraphs() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var node = mapper.readTree("""
                {"type":"doc","content":[
                  {"type":"paragraph","content":[{"type":"text","text":"Hello"}]},
                  {"type":"paragraph","content":[{"type":"text","text":"World"}]}
                ]}
                """);
        assertThat(IntegrationConnectService.adfToPlainText(node)).isEqualTo("Hello\nWorld");
    }

    private IntegrationHttpGateway serviceGateway() {
        return new IntegrationHttpGateway() {
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
        };
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
                provider, null, baseUrl, token, username, email, organization, workspace, projectKey, spaceKey
        );
    }

    private static final class MemoryProjectIntegrationStore implements ProjectIntegrationStore {
        private final Map<String, com.talentserv.blink.dto.StoredIntegration> rows = new HashMap<>();

        @Override
        public void upsert(com.talentserv.blink.dto.StoredIntegration integration) {
            if (integration == null || integration.projectId() == null || integration.provider() == null) {
                return;
            }
            rows.put(integration.projectId() + ":" + integration.provider(), integration);
        }

        @Override
        public java.util.Optional<com.talentserv.blink.dto.StoredIntegration> find(Long projectId, String provider) {
            if (projectId == null || provider == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.ofNullable(rows.get(projectId + ":" + provider));
        }
    }
}
