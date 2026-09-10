package com.talentserv.blink.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "blink")
public class BlinkProperties {

    /**
     * Path to the automation_sdlc tree copied into generated workspace zips.
     */
    private String automationSdlcPath = "../automation_sdlc";

    /**
     * Seeded operator used as created_by when the wizard has no login yet.
     */
    private String demoUserEmail = "blink.system@talentserv.com";

    /**
     * Comma-separated origins allowed to call /api from the React app.
     */
    private String corsOrigins = "http://localhost:5173,http://127.0.0.1:5173";

    /**
     * Virtual AWS / local setup agent endpoint (POST JSON, returns setup proposal).
     */
    private String setupAgentUrl = "http://127.0.0.1:8091/setup/start";

    /**
     * Agent runtime URL (defaults to live AWS Lambda endpoint; local dev uses http://127.0.0.1:8787).
     */
    private String agentRuntimeUrl = "https://z5i3yybrx1.execute-api.us-west-2.amazonaws.com";

    /**
     * Shared bearer token between Blink Backend and the Agent Runtime.
     */
    private String agentRuntimeToken = "blink-groom-2026";

    /**
     * When set and AWS keys are present, invoke this Lambda directly instead of
     * the API Gateway URL (API Gateway times out at 29s; Luna reasoning needs longer).
     */
    private String agentRuntimeLambdaFunction = "blink-agent-runtime";

    private String awsAccessKeyId = "";
    private String awsSecretAccessKey = "";
    private String awsRegion = "us-west-2";
    private String s3BucketName = "";
    private String s3PublicBaseUrl = "";
    private String automationSdlcGitUrl = "https://github.com/AtulTalentServ/automation_sdlc.git";
    /**
     * Executable used to invoke the framework's deterministic setup projector.
     * Docker supplies python3; Windows developers can set BLINK_CANONICAL_SETUP_PYTHON=python.
     */
    private String canonicalSetupPython = "python3";
    private Duration canonicalSetupTimeout = Duration.ofSeconds(90);

    /**
     * Atlassian OAuth 2.0 (3LO) client credentials.
     */
    private String jiraClientId = "";
    private String jiraClientSecret = "";
    private String jiraRedirectUri = "";
    private String jiraScopes = "read:jira-work write:jira-work read:jira-user read:me offline_access";
    /**
     * GitHub OAuth App credentials. Users sign in on Blink; PAT goes in the zip .env later.
     */
    private String githubClientId = "";
    private String githubClientSecret = "";
    private String githubRedirectUri = "";
    private String githubScopes = "repo read:org user:email";
    /**
     * Passphrase used to derive the AES key for per-project GitHub/Jira tokens.
     */
    private String integrationSecretKey = "";

    public String getAutomationSdlcPath() {
        return automationSdlcPath;
    }

    public void setAutomationSdlcPath(String automationSdlcPath) {
        this.automationSdlcPath = automationSdlcPath;
    }

    public String getDemoUserEmail() {
        return demoUserEmail;
    }

    public void setDemoUserEmail(String demoUserEmail) {
        this.demoUserEmail = demoUserEmail;
    }

    public String getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(String corsOrigins) {
        this.corsOrigins = corsOrigins;
    }

    public String getSetupAgentUrl() {
        return setupAgentUrl;
    }

    public void setSetupAgentUrl(String setupAgentUrl) {
        this.setupAgentUrl = setupAgentUrl;
    }

    public String getAgentRuntimeUrl() {
        return agentRuntimeUrl;
    }

    public void setAgentRuntimeUrl(String agentRuntimeUrl) {
        this.agentRuntimeUrl = agentRuntimeUrl;
    }

    public String getAgentRuntimeToken() {
        return agentRuntimeToken;
    }

    public void setAgentRuntimeToken(String agentRuntimeToken) {
        this.agentRuntimeToken = agentRuntimeToken;
    }

    public String getAgentRuntimeLambdaFunction() {
        return agentRuntimeLambdaFunction;
    }

    public void setAgentRuntimeLambdaFunction(String agentRuntimeLambdaFunction) {
        this.agentRuntimeLambdaFunction = agentRuntimeLambdaFunction;
    }

    public String getAwsAccessKeyId() {
        return awsAccessKeyId;
    }

    public void setAwsAccessKeyId(String awsAccessKeyId) {
        this.awsAccessKeyId = awsAccessKeyId;
    }

    public String getAwsSecretAccessKey() {
        return awsSecretAccessKey;
    }

    public void setAwsSecretAccessKey(String awsSecretAccessKey) {
        this.awsSecretAccessKey = awsSecretAccessKey;
    }

    public String getAwsRegion() {
        return awsRegion;
    }

    public void setAwsRegion(String awsRegion) {
        this.awsRegion = awsRegion;
    }

    public String getS3BucketName() {
        return s3BucketName;
    }

    public void setS3BucketName(String s3BucketName) {
        this.s3BucketName = s3BucketName;
    }

    public String getS3PublicBaseUrl() {
        return s3PublicBaseUrl;
    }

    public void setS3PublicBaseUrl(String s3PublicBaseUrl) {
        this.s3PublicBaseUrl = s3PublicBaseUrl;
    }

    public String getAutomationSdlcGitUrl() {
        return automationSdlcGitUrl;
    }

    public void setAutomationSdlcGitUrl(String automationSdlcGitUrl) {
        this.automationSdlcGitUrl = automationSdlcGitUrl;
    }

    public String getCanonicalSetupPython() {
        return canonicalSetupPython;
    }

    public void setCanonicalSetupPython(String canonicalSetupPython) {
        this.canonicalSetupPython = canonicalSetupPython;
    }

    public Duration getCanonicalSetupTimeout() {
        return canonicalSetupTimeout;
    }

    public void setCanonicalSetupTimeout(Duration canonicalSetupTimeout) {
        this.canonicalSetupTimeout = canonicalSetupTimeout;
    }

    public String getJiraClientId() {
        return jiraClientId;
    }

    public void setJiraClientId(String jiraClientId) {
        this.jiraClientId = jiraClientId;
    }

    public String getJiraClientSecret() {
        return jiraClientSecret;
    }

    public void setJiraClientSecret(String jiraClientSecret) {
        this.jiraClientSecret = jiraClientSecret;
    }

    public String getJiraRedirectUri() {
        return jiraRedirectUri;
    }

    public void setJiraRedirectUri(String jiraRedirectUri) {
        this.jiraRedirectUri = jiraRedirectUri;
    }

    public String getJiraScopes() {
        return jiraScopes;
    }

    public void setJiraScopes(String jiraScopes) {
        this.jiraScopes = jiraScopes;
    }

    public String getGithubClientId() {
        return githubClientId;
    }

    public void setGithubClientId(String githubClientId) {
        this.githubClientId = githubClientId;
    }

    public String getGithubClientSecret() {
        return githubClientSecret;
    }

    public void setGithubClientSecret(String githubClientSecret) {
        this.githubClientSecret = githubClientSecret;
    }

    public String getGithubRedirectUri() {
        return githubRedirectUri;
    }

    public void setGithubRedirectUri(String githubRedirectUri) {
        this.githubRedirectUri = githubRedirectUri;
    }

    public String getGithubScopes() {
        return githubScopes;
    }

    public void setGithubScopes(String githubScopes) {
        this.githubScopes = githubScopes;
    }

    public String getIntegrationSecretKey() {
        return integrationSecretKey;
    }

    public void setIntegrationSecretKey(String integrationSecretKey) {
        this.integrationSecretKey = integrationSecretKey;
    }

    public boolean s3Enabled() {
        return !blank(s3BucketName) && !blank(awsAccessKeyId) && !blank(awsSecretAccessKey) && !blank(awsRegion);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
