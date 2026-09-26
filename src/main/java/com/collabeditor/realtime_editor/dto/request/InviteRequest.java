package com.collabeditor.realtime_editor.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InviteRequest {

    @NotBlank(message = "Username is required")
    private String username;
}