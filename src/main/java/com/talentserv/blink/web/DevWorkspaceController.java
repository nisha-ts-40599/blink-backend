package com.talentserv.blink.web;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talentserv.blink.dto.S3WorkspaceDeleteResponse;
import com.talentserv.blink.dto.S3WorkspaceListResponse;
import com.talentserv.blink.service.OtpLoginService;
import com.talentserv.blink.service.S3WorkspaceService;

/**
 * Developer-panel endpoints for Blink-owned S3 workspace folders.
 * List/delete only touch prefixes that match {@code *_workspace} and carry a Blink marker.
 */
@RestController
@RequestMapping("/api/dev/workspaces")
public class DevWorkspaceController {

    private final S3WorkspaceService s3WorkspaceService;
    private final OtpLoginService otpLoginService;

    public DevWorkspaceController(S3WorkspaceService s3WorkspaceService, OtpLoginService otpLoginService) {
        this.s3WorkspaceService = s3WorkspaceService;
        this.otpLoginService = otpLoginService;
    }

    @GetMapping
    public S3WorkspaceListResponse list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        otpLoginService.requireSession(authorization);
        return s3WorkspaceService.listBlinkWorkspaces();
    }

    @DeleteMapping("/{folder}")
    public S3WorkspaceDeleteResponse deleteOne(
            @PathVariable String folder,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        otpLoginService.requireSession(authorization);
        return s3WorkspaceService.deleteBlinkWorkspace(folder);
    }

    @DeleteMapping
    public S3WorkspaceDeleteResponse deleteAll(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        otpLoginService.requireSession(authorization);
        return s3WorkspaceService.deleteAllBlinkWorkspaces();
    }
}
