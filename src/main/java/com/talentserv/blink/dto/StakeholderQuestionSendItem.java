package com.talentserv.blink.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;

public record StakeholderQuestionSendItem(
        @JsonProperty("question_id") @NotBlank String questionId,
        @NotBlank String question,
        @JsonProperty("recipient_email") @NotBlank String recipientEmail,
        @JsonProperty("recipient_name") String recipientName,
        String role,
        @JsonProperty("project_name") String projectName,
        @JsonProperty("proposed_answer") String proposedAnswer
) {
}
