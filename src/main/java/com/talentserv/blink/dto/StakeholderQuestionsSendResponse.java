package com.talentserv.blink.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StakeholderQuestionsSendResponse(
        List<StakeholderQuestionDeliveryResult> results,
        @JsonProperty("delivery_mode") String deliveryMode,
        @JsonProperty("outbox_dir") String outboxDir
) {
}
