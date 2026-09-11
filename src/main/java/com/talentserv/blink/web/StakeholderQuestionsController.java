package com.talentserv.blink.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talentserv.blink.dto.StakeholderQuestionsSendRequest;
import com.talentserv.blink.dto.StakeholderQuestionsSendResponse;
import com.talentserv.blink.service.StakeholderEmailService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/stakeholder-questions")
public class StakeholderQuestionsController {

    private final StakeholderEmailService stakeholderEmailService;

    public StakeholderQuestionsController(StakeholderEmailService stakeholderEmailService) {
        this.stakeholderEmailService = stakeholderEmailService;
    }

    @PostMapping("/send")
    public StakeholderQuestionsSendResponse send(@Valid @RequestBody StakeholderQuestionsSendRequest request) {
        return stakeholderEmailService.send(request);
    }
}
