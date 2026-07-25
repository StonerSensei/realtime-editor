package com.collabeditor.realtime_editor.websocket;

import com.collabeditor.realtime_editor.dto.request.CodeExecutionRequest;
import com.collabeditor.realtime_editor.dto.response.CodeExecutionResponse;
import com.collabeditor.realtime_editor.service.CodeExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodeExecutionWebSocketHandler extends TextWebSocketHandler {

    private final CodeExecutionService codeExecutionService;
    private final ObjectMapper objectMapper;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            CodeExecutionRequest request = objectMapper.readValue(message.getPayload(), CodeExecutionRequest.class);
            log.info("Code execution via WebSocket for language: {}", request.getLanguage());

            CodeExecutionResponse response = codeExecutionService.executeCode(request);

            // Send stdout
            if (response.getStdout() != null && !response.getStdout().isEmpty()) {
                session.sendMessage(new TextMessage(response.getStdout()));
            }

            // Send stderr if present
            if (response.getStderr() != null && !response.getStderr().isEmpty()) {
                session.sendMessage(new TextMessage("Error: " + response.getStderr()));
            }

        } catch (Exception e) {
            log.error("WebSocket code execution error: {}", e.getMessage());
            if (session.isOpen()) {
                session.sendMessage(new TextMessage("Execution error: " + e.getMessage()));
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("Code execution WebSocket connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("Code execution WebSocket closed: {} (status: {})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Code execution WebSocket transport error (session: {}): {}", session.getId(), exception.getMessage());
    }
}
