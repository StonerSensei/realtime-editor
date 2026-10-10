package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.dto.request.CodeExecutionRequest;
import com.collabeditor.realtime_editor.dto.response.CodeExecutionResponse;

/**
 * Strategy for running user-submitted code.
 * <p>
 * Two implementations exist, selected by the {@code execution.mode} property:
 * <ul>
 *   <li>{@code docker} — {@link DockerCodeExecutor}, runs ephemeral containers on the
 *       local Docker daemon. Used when self-hosting.</li>
 *   <li>{@code remote} — {@link RemoteCodeExecutor}, delegates to a separate worker
 *       service over HTTP. Used when the app is hosted on a platform without Docker.</li>
 * </ul>
 */
public interface CodeExecutor {
    CodeExecutionResponse execute(CodeExecutionRequest request);
}