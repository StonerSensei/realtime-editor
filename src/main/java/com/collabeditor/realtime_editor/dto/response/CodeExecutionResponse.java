package com.collabeditor.realtime_editor.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeExecutionResponse {

    private String stdout;
    private String stderr;
    private int exitCode;
    private String language;
}
