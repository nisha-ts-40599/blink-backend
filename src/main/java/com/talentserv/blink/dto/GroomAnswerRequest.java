package com.talentserv.blink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GroomAnswerRequest(
        @NotBlank(message = "Each answer needs a questionId.") String questionId,
        @NotBlank(message = "Each answer needs an optionId.") String optionId,
        @Size(max = 500) String optionLabel,
        @Size(max = 20000) String otherText
) {
}
