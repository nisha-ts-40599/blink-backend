package com.talentserv.blink.web;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talentserv.blink.dto.BlinkJiraIssueDeleteResponse;
import com.talentserv.blink.dto.BlinkJiraIssueListResponse;
import com.talentserv.blink.service.IntegrationConnectService;
import com.talentserv.blink.service.OtpLoginService;

/**
 * Developer-panel endpoints for Blink-created Jira issues only
 * ({@code Source epic:} / {@code Source story:} markers). Never wipes a whole Jira project.
 */
@RestController
@RequestMapping("/api/dev/jira/issues")
public class DevJiraController {

    private final IntegrationConnectService integrationConnectService;
    private final OtpLoginService otpLoginService;

    public DevJiraController(IntegrationConnectService integrationConnectService, OtpLoginService otpLoginService) {
        this.integrationConnectService = integrationConnectService;
        this.otpLoginService = otpLoginService;
    }

    @GetMapping
    public BlinkJiraIssueListResponse list(
            @RequestParam String projectId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        otpLoginService.requireSession(authorization);
        return integrationConnectService.listBlinkJiraIssues(projectId);
    }

    @DeleteMapping("/{issueKey}")
    public BlinkJiraIssueDeleteResponse deleteOne(
            @PathVariable String issueKey,
            @RequestParam String projectId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        otpLoginService.requireSession(authorization);
        return integrationConnectService.deleteBlinkJiraIssue(projectId, issueKey);
    }

    @DeleteMapping
    public BlinkJiraIssueDeleteResponse deleteAll(
            @RequestParam String projectId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        otpLoginService.requireSession(authorization);
        return integrationConnectService.deleteAllBlinkJiraIssues(projectId);
    }
}
