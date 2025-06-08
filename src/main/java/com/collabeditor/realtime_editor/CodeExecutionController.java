package com.collabeditor.realtime_editor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@RestController
@RequestMapping("/api")
public class CodeExecutionController {

    private final RestTemplate restTemplate = new RestTemplate();

    @PostMapping("/execute")
    public ResponseEntity<?> executeCode(@RequestBody CodeExecutionRequest request) {
        String pistonApiUrl = "https://emkc.org/api/v2/piston/execute";

        Map<String, Object> body = new HashMap<>();
        body.put("language", mapLanguage(request.getLanguage()));
        body.put("version", "*");
        body.put("stdin", request.getInput() != null ? request.getInput() : "");

        List<Map<String, String>> files = List.of(
                Map.of("name", "main." + getExtension(request.getLanguage()), "content", request.getCode())
        );
        body.put("files", files);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(pistonApiUrl, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                Map<?, ?> run = (Map<?, ?>) response.getBody().get("run");
                return ResponseEntity.ok(run.get("stdout"));
            } else {
                return ResponseEntity.status(response.getStatusCode()).body("Execution failed.");
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Piston error: " + e.getMessage());
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
