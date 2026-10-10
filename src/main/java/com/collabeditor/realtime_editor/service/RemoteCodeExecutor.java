package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.dto.request.CodeExecutionRequest;
import com.collabeditor.realtime_editor.dto.response.CodeExecutionResponse;
import com.collabeditor.realtime_editor.exception.CodeExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Delegates execution to a separate worker service that has Docker available.
 * Used when the application itself runs on a platform without a Docker daemon.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "execution.mode", havingValue = "remote")
public class RemoteCodeExecutor implements CodeExecutor {

    private final String workerUrl;
    private final String workerSecret;
    private final HttpClient http;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public RemoteCodeExecutor(
            @Value("${execution.worker-url:}") String workerUrl,
            @Value("${execution.worker-secret:}") String workerSecret,
            @Value("${execution.timeout-seconds:15}") int timeoutSeconds) {
        this.workerUrl = workerUrl;
        this.workerSecret = workerSecret;
        // Allow the worker a little longer than the sandbox timeout, plus network latency.
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds + 20L);
    }

    private final Duration requestTimeout;

    @Override
    public CodeExecutionResponse execute(CodeExecutionRequest request) {
        if (workerUrl == null || workerUrl.isBlank()) {
            throw new CodeExecutionException(
                    "The code execution server is currently offline. Editing and chat still work.");
        }

        try {
            String body = mapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(workerUrl + "/execute"))
                    .header("Content-Type", "application/json")
                    .header("X-Worker-Secret", workerSecret)
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Execution worker returned {}", response.statusCode());
                throw new CodeExecutionException(
                        "The code execution server rejected the request (HTTP " + response.statusCode() + ").");
            }
            return mapper.readValue(response.body(), CodeExecutionResponse.class);

        } catch (CodeExecutionException e) {
            throw e;
        } catch (java.net.http.HttpConnectTimeoutException | java.net.ConnectException e) {
            log.warn("Execution worker unreachable: {}", e.getMessage());
            throw new CodeExecutionException(
                    "The code execution server is currently offline. Editing and chat still work.");
        } catch (Exception e) {
            log.error("Remote execution failed: {}", e.getMessage());
            throw new CodeExecutionException("Code execution failed: " + e.getMessage(), e);
        }
    }
}