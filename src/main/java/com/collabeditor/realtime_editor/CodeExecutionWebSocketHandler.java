package com.collabeditor.realtime_editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;

public class CodeExecutionWebSocketHandler extends TextWebSocketHandler {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            Map<String, Object> request = objectMapper.readValue(message.getPayload(), Map.class);

            String language = (String) request.get("language");
            String code = (String) request.get("code");
            String input = (String) request.get("input");

            executeCodeAndSendResults(session, language, code, input);
        } catch (Exception e) {
            session.sendMessage(new TextMessage("❌ Error parsing request: " + e.getMessage()));
        }
    }

    private void executeCodeAndSendResults(WebSocketSession session, String language, String code, String input) {
        try {
            String pistonApiUrl = "https://emkc.org/api/v2/piston/execute";

            Map<String, Object> body = new HashMap<>();
            body.put("language", mapLanguage(language));
            body.put("version", "*");
            body.put("stdin", input != null ? input : "");

            List<Map<String, String>> files = List.of(
                    Map.of("name", "main." + getExtension(language), "content", code)
            );
            body.put("files", files);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(pistonApiUrl, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                Map<String, Object> run = (Map<String, Object>) responseBody.get("run");

                if (run != null) {
                    String stdout = (String) run.get("stdout");
                    String stderr = (String) run.get("stderr");

                    if (stdout != null && !stdout.isEmpty()) {
                        session.sendMessage(new TextMessage(stdout));
                    }
                    if (stderr != null && !stderr.isEmpty()) {
                        session.sendMessage(new TextMessage("❌ Error: " + stderr));
                    }
                } else {
                    session.sendMessage(new TextMessage("❌ No execution result received"));
                }
            } else {
                session.sendMessage(new TextMessage("❌ Execution failed"));
            }
        } catch (Exception e) {
            try {
                session.sendMessage(new TextMessage("❌ Execution error: " + e.getMessage()));
            } catch (Exception ex) {
                System.err.println("Failed to send error message: " + ex.getMessage());
            }
        }
    }

    private String getExtension(String lang) {
        return switch (lang) {
            case "c" -> "c";
            case "cpp" -> "cpp";
            case "javascript" -> "js";
            default -> "txt";
        };
    }

    private String mapLanguage(String lang) {
        return switch (lang) {
            case "cpp" -> "cpp";
            case "c" -> "c";
            case "javascript" -> "javascript";
            default -> lang;
        };
    }
}