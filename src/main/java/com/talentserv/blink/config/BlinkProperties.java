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
}
