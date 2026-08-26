package com.talentserv.blink.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talentserv.blink.dto.CreateRepositoriesRequest;
import com.talentserv.blink.dto.CreateRepositoriesResponse;
import com.talentserv.blink.dto.IntegrationConnectRequest;
import com.talentserv.blink.dto.IntegrationConnectResponse;
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
}
