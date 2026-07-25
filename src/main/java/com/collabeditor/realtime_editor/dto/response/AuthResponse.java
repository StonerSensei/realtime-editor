package com.collabeditor.realtime_editor.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {

    // Short-lived access token (JWT).
    private String token;

    // Long-lived refresh token (opaque, revocable).
    private String refreshToken;

    private String username;
    private String email;
    private String message;
}
