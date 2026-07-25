package com.collabeditor.realtime_editor.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CodeExecutionRequest {

    @NotBlank(message = "Language is required")
    @Pattern(regexp = "^(javascript|python|cpp|c|java)$", message = "Unsupported language")
    private String language;

    @NotBlank(message = "Code cannot be empty")
    @Size(max = 100000, message = "Code exceeds maximum length of 100,000 characters")
    private String code;

    private String input;
}
