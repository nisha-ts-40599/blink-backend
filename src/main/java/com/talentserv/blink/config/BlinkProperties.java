package com.talentserv.blink.config;

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
     * Cloudflare Worker agent runtime (wrangler dev defaults to :8787).
     */
    private String agentRuntimeUrl = "https://blink-agent-runtime.rushikesh-kate.workers.dev";

    /**
     * Shared bearer token between Blink Backend and the Worker.
     */
    private String agentRuntimeToken = "";

    private String awsAccessKeyId = "";
    private String awsSecretAccessKey = "";
    private String awsRegion = "us-west-2";
    private String s3BucketName = "";
    private String s3PublicBaseUrl = "";
    private String automationSdlcGitUrl = "https://github.com/AtulTalentServ/automation_sdlc.git";

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

    public boolean s3Enabled() {
        return !blank(s3BucketName) && !blank(awsAccessKeyId) && !blank(awsSecretAccessKey) && !blank(awsRegion);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
