package com.talentserv.blink.web;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talentserv.blink.dto.CreateRepositoriesRequest;
import com.talentserv.blink.dto.CreateRepositoriesResponse;
import com.talentserv.blink.dto.IntegrationBindingRequest;
import com.talentserv.blink.dto.IntegrationConnectRequest;
import com.talentserv.blink.dto.IntegrationConnectResponse;
import com.talentserv.blink.dto.JiraOAuthExchangeRequest;
import com.talentserv.blink.dto.JiraOAuthUrlResponse;
import com.talentserv.blink.dto.JiraProjectDto;
import com.talentserv.blink.dto.JiraProjectsRequest;
import com.talentserv.blink.dto.JiraCreateIssuesRequest;
import com.talentserv.blink.dto.JiraCreateIssuesResponse;
import com.talentserv.blink.dto.JiraEpicSpec;
import com.talentserv.blink.dto.JiraStorySpec;
import com.talentserv.blink.service.IntegrationConnectService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationConnectController {

    private final IntegrationConnectService integrationConnectService;

    public IntegrationConnectController(IntegrationConnectService integrationConnectService) {
        this.integrationConnectService = integrationConnectService;
    }

    @PostMapping("/connect")
    public IntegrationConnectResponse connect(@Valid @RequestBody IntegrationConnectRequest request) {
        return integrationConnectService.connect(request);
    }

    @PostMapping("/repositories")
    public CreateRepositoriesResponse createRepositories(@Valid @RequestBody CreateRepositoriesRequest request) {
        return integrationConnectService.createRepositories(request);
    }

    @GetMapping("/jira/oauth/url")
    public JiraOAuthUrlResponse getJiraOAuthUrl() {
        return integrationConnectService.getJiraOAuthUrl();
    }

    @PostMapping("/jira/oauth/exchange")
    public IntegrationConnectResponse exchangeJiraOAuth(@Valid @RequestBody JiraOAuthExchangeRequest request) {
        return integrationConnectService.exchangeJiraOAuth(request);
    }

    @PostMapping("/jira/projects")
    public List<JiraProjectDto> fetchJiraProjects(@RequestBody JiraProjectsRequest request) {
        return integrationConnectService.fetchJiraProjects(request);
    }

    @PostMapping("/jira/issues")
    public JiraCreateIssuesResponse createJiraIssues(@Valid @RequestBody JiraCreateIssuesRequest request) {
        return integrationConnectService.createJiraIssues(request);
    }

    @PostMapping("/binding")
    public IntegrationConnectResponse saveBinding(@Valid @RequestBody IntegrationBindingRequest request) {
        return integrationConnectService.saveBinding(request);
    }

    @GetMapping(value = "/jira/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    public String jiraOAuthCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription
    ) {
        String safeCode = code != null ? code.replace("\"", "\\\"").replace("'", "\\'") : "";
        String safeState = state != null ? state.replace("\"", "\\\"").replace("'", "\\'") : "";
        String safeError = error != null ? (error + (errorDescription != null ? ": " + errorDescription : "")).replace("\"", "\\\"").replace("'", "\\'") : "";

        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Atlassian Authorization</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; background: #0f172a; color: #f8fafc; text-align: center; }
                        .card { padding: 2rem; border-radius: 12px; background: #1e293b; border: 1px solid #334155; max-width: 400px; }
                        h2 { margin-top: 0; color: #38bdf8; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <h2>Atlassian Connected</h2>
                        <p id="msg">Completing authorization with Blink...</p>
                    </div>
                    <script>
                        const code = "%s";
                        const state = "%s";
                        const error = "%s";
                        if (window.opener) {
                            window.opener.postMessage({
                                type: 'JIRA_OAUTH_RESPONSE',
                                code: code || null,
                                state: state || null,
                                error: error || null
                            }, '*');
                            document.getElementById('msg').innerText = 'Closing popup window...';
                            setTimeout(() => window.close(), 600);
                        } else {
                            if (code) {
                                window.location.href = '/?jira_code=' + encodeURIComponent(code);
                            } else {
                                document.getElementById('msg').innerText = error || 'Authorization failed.';
                            }
                        }
                    </script>
                </body>
                </html>
                """.formatted(safeCode, safeState, safeError);
    }
}
