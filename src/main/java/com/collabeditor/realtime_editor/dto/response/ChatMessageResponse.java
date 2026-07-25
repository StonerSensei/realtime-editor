package com.collabeditor.realtime_editor.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@AllArgsConstructor
public class ChatMessageResponse {

    private String username;
    private String content;
    private Instant timestamp;
}
