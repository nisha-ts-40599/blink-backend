package com.talentserv.blink.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record StakeholderQuestionsSendRequest(
        @NotEmpty List<@Valid StakeholderQuestionSendItem> questions
) {
}
