package com.talentserv.blink.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import com.talentserv.blink.dto.GroomClarifyRequest;
import com.talentserv.blink.service.AgentRuntimeService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/grooming")
public class GroomingController {

    private final AgentRuntimeService agentRuntimeService;

    public GroomingController(AgentRuntimeService agentRuntimeService) {
        this.agentRuntimeService = agentRuntimeService;
    }

    @PostMapping("/clarify")
    public JsonNode clarify(@Valid @RequestBody GroomClarifyRequest body) {
        return agentRuntimeService.invokeClarify(body);
    }
}
