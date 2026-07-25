package com.collabeditor.realtime_editor.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
public class SaveSnapshotRequest {

    @NotBlank(message = "Room ID is required")
    private String roomId;

    /** Legacy single-file content (still accepted). */
    @Size(max = 200000, message = "Code exceeds maximum length")
    private String code;

    /** Multi-file project: filename -> content. Preferred over {@code code}. */
    private Map<String, String> files;

    private String language;
}
