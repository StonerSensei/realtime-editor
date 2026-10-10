package com.collabeditor.realtime_editor.controller;

import com.collabeditor.realtime_editor.dto.request.CodeExecutionRequest;
import com.collabeditor.realtime_editor.dto.response.CodeExecutionResponse;
import com.collabeditor.realtime_editor.service.CodeExecutor;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CodeExecutionController {

    private final CodeExecutor codeExecutor;

    @PostMapping("/execute")
    public ResponseEntity<CodeExecutionResponse> executeCode(@Valid @RequestBody CodeExecutionRequest request) {
        log.info("Code execution request for language: {}", request.getLanguage());
        CodeExecutionResponse response = codeExecutor.execute(request);
        return ResponseEntity.ok(response);
    }
}
