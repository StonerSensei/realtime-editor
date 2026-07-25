package com.collabeditor.realtime_editor.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@AllArgsConstructor
public class SnapshotResponse {

    private String id;
    private String roomId;
    private String code;
    private java.util.Map<String, String> files;
    private String language;
    private String savedBy;
    private Instant timestamp;
}
