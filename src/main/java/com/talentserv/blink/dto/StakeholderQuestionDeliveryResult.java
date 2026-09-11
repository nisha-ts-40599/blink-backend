package com.talentserv.blink.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StakeholderQuestionDeliveryResult(
        @JsonProperty("question_id") String questionId,
        String status,
        @JsonProperty("delivery_method") String deliveryMethod,
        String message
) {
}
