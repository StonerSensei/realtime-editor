package com.collabeditor.realtime_editor.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@AllArgsConstructor
public class InvitationResponse {
    private String roomId;
    private String language;
    private String owner;
    private Instant createdAt;
}