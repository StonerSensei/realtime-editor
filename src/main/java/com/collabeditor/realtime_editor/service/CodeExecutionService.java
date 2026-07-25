package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.dto.request.CodeExecutionRequest;
import com.collabeditor.realtime_editor.dto.response.CodeExecutionResponse;
import com.collabeditor.realtime_editor.exception.CodeExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Executes user-submitted code inside ephemeral, sandboxed Docker containers.
 * <p>
 * Each run:
 * <ul>
 *   <li>writes the source + stdin to a throwaway temp directory,</li>
 *   <li>launches a one-shot container ({@code --rm}) with the source mounted read-only,</li>
 *   <li>applies resource limits ({@code --network none}, memory, CPU, PID caps),</li>
 *   <li>enforces a wall-clock timeout and force-kills the container if exceeded.</li>
 * </ul>
 * This removes any dependency on external code-execution APIs.
 */
@Slf4j
@Service
public class CodeExecutionService {

    private final int timeoutSeconds;
    private final String memoryLimit;
    private final String cpuLimit;
    private final int maxOutputChars;
    private final Path workDirBase;

    public CodeExecutionService(
            @Value("${execution.timeout-seconds:15}") int timeoutSeconds,
            @Value("${execution.memory-limit:256m}") String memoryLimit,
            @Value("${execution.cpu-limit:0.5}") String cpuLimit,
            @Value("${execution.max-output-chars:100000}") int maxOutputChars,
            @Value("${execution.work-dir:}") String workDir) {
        this.timeoutSeconds = timeoutSeconds;
        this.memoryLimit = memoryLimit;
        this.cpuLimit = cpuLimit;
        this.maxOutputChars = maxOutputChars;
        // When the app runs inside a container and shells out to the host Docker daemon
        // (Docker-out-of-Docker), the "-v <path>:/code" mount is resolved by the HOST.
        // So the work dir must be a path shared (same absolute path) between the app
        // container and the host. Configure it via EXEC_WORK_DIR; default to the JVM temp dir.
        this.workDirBase = Path.of((workDir == null || workDir.isBlank())
                ? System.getProperty("java.io.tmpdir")
                : workDir);
    }

    /** Per-language container image, source filename, and shell command. */
    private record LangConfig(String image, String filename, String script) {}

    private LangConfig configFor(String language) {
        return switch (language) {
            case "python" -> new LangConfig(
                    "python:3.11-alpine", "main.py",
                    "python3 /code/main.py < /code/input.txt");
            case "javascript" -> new LangConfig(
                    "node:20-alpine", "main.js",
                    "node /code/main.js < /code/input.txt");
            case "c" -> new LangConfig(
                    "gcc:13", "main.c",
                    "gcc /code/main.c -o /tmp/prog && /tmp/prog < /code/input.txt");
            case "cpp" -> new LangConfig(
                    "gcc:13", "main.cpp",
                    "g++ /code/main.cpp -o /tmp/prog && /tmp/prog < /code/input.txt");
            case "java" -> new LangConfig(
                    "eclipse-temurin:21-jdk", "Main.java",
                    "javac -d /tmp /code/Main.java && cd /tmp && java Main < /code/input.txt");
            default -> throw new CodeExecutionException("Unsupported language: " + language);
        };
    }

    public CodeExecutionResponse executeCode(CodeExecutionRequest request) {
        String language = request.getLanguage();
        log.info("Executing {} code in ephemeral container", language);

        LangConfig config = configFor(language);
        Path workDir = null;
        String containerName = "collabide-exec-" + UUID.randomUUID();

        try {
            // Prepare an isolated work directory under the (host-shared) base dir
            Files.createDirectories(workDirBase);
            workDir = Files.createTempDirectory(workDirBase, "collabide-exec-");
            Files.writeString(workDir.resolve(config.filename()), request.getCode());
            Files.writeString(workDir.resolve("input.txt"),
                    request.getInput() != null ? request.getInput() : "");

            return runInContainer(config, workDir, containerName, language);

        } catch (CodeExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Code execution failed: {}", e.getMessage());
            throw new CodeExecutionException("Code execution failed: " + e.getMessage(), e);
        } finally {
            cleanup(workDir);
        }
    }

    private CodeExecutionResponse runInContainer(LangConfig config, Path workDir,
                                                 String containerName, String language) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--name", containerName,
                "--network", "none",
                "--memory", memoryLimit,
                "--cpus", cpuLimit,
                "--pids-limit", "128",
                "-v", workDir.toAbsolutePath() + ":/code:ro",
                config.image(),
                "sh", "-c", config.script()
        ));

        Process process = new ProcessBuilder(command).start();

        // Read stdout/stderr concurrently to avoid pipe-buffer deadlocks
        CompletableFuture<String> stdoutFuture =
                CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
        CompletableFuture<String> stderrFuture =
                CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

        if (!finished) {
            log.warn("Execution timed out after {}s, killing container {}", timeoutSeconds, containerName);
            forceKill(containerName);
            process.destroyForcibly();
            return CodeExecutionResponse.builder()
                    .stdout("")
                    .stderr("Execution timed out after " + timeoutSeconds + " seconds.")
                    .exitCode(124)
                    .language(language)
                    .build();
        }

        String stdout = truncate(stdoutFuture.get(5, TimeUnit.SECONDS));
        String stderr = truncate(stderrFuture.get(5, TimeUnit.SECONDS));
        int exitCode = process.exitValue();

        return CodeExecutionResponse.builder()
                .stdout(stdout)
                .stderr(stderr)
                .exitCode(exitCode)
                .language(language)
                .build();
    }

    private String readStream(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private String truncate(String output) {
        if (output != null && output.length() > maxOutputChars) {
            return output.substring(0, maxOutputChars) + "\n... [output truncated]";
        }
        return output;
    }

    private void forceKill(String containerName) {
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to force-kill container {}: {}", containerName, e.getMessage());
        }
    }

    private void cleanup(Path workDir) {
        if (workDir == null) return;
        try (var paths = Files.walk(workDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to clean temp dir {}: {}", workDir, e.getMessage());
        }
    }
}
