package com.collabeditor.realtime_editor.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateRoomRequest {

    @NotBlank(message = "Room ID is required")
    @Size(min = 3, max = 50, message = "Room ID must be between 3 and 50 characters")
    @Pattern(regexp = "^[a-zA-Z0-9-_]+$", message = "Room ID can only contain letters, numbers, hyphens, and underscores")
    private String roomId;

    @NotBlank(message = "Language is required")
    @Pattern(regexp = "^(javascript|python|cpp|c|java)$", message = "Unsupported language")
    private String language;
}
