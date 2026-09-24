package com.collabeditor.realtime_editor.controller;

import com.collabeditor.realtime_editor.BaseIntegrationTest;
import com.collabeditor.realtime_editor.dto.request.RegisterRequest;
import com.collabeditor.realtime_editor.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end: an access token works on a protected endpoint, and is rejected with 401 the
 * moment it is logged out, well before its natural expiry. Passes whether the blacklist is
 * served by Redis or by the local fallback, since the same instance handles both calls.
 */
@AutoConfigureMockMvc
class LogoutBlacklistIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private String accessToken;
    private String refreshToken;

    @BeforeEach
    void registerUser() throws Exception {
        userRepository.deleteAll();

        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("logout-" + UUID.randomUUID().toString().substring(0, 8));
        reg.setEmail(reg.getUsername() + "@example.com");
        reg.setPassword("password123");

        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        accessToken = json.get("token").asText();
        refreshToken = json.get("refreshToken").asText();
    }

    @Test
    @DisplayName("Protected call works, then returns 401 with the same token after logout")
    void accessToken_shouldBeRejectedAfterLogout() throws Exception {
        mockMvc.perform(get("/api/chat/any-room").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/chat/any-room").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Logout without the Authorization header still revokes the refresh token")
    void logoutWithoutAccessToken_shouldRevokeRefreshOnly() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isNoContent());

        // Access token was not sent, so it stays valid until it expires...
        mockMvc.perform(get("/api/chat/any-room").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // ...but the refresh token can no longer mint new ones.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());
    }
}