package com.collabeditor.realtime_editor.controller;

import com.collabeditor.realtime_editor.BaseIntegrationTest;
import com.collabeditor.realtime_editor.dto.request.CreateRoomRequest;
import com.collabeditor.realtime_editor.dto.request.RegisterRequest;
import com.collabeditor.realtime_editor.repository.RoomRepository;
import com.collabeditor.realtime_editor.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class RoomControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    private String authToken;

    @BeforeEach
    void setUp() throws Exception {
        roomRepository.deleteAll();
        userRepository.deleteAll();

        // Register a user and get a token
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("roomtester");
        reg.setEmail("room@test.com");
        reg.setPassword("password123");

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        authToken = objectMapper.readTree(responseBody).get("token").asText();
    }

    @Test
    @DisplayName("POST /api/rooms/host - should create a room with valid token")
    void createRoom_shouldReturn201WithValidToken() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest();
        request.setRoomId("my-test-room");
        request.setLanguage("javascript");

        mockMvc.perform(post("/api/rooms/host")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId", is("my-test-room")))
                .andExpect(jsonPath("$.language", is("javascript")))
                .andExpect(jsonPath("$.message", is("Room created successfully")))
                .andExpect(jsonPath("$.createdAt", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/rooms/host - should fail without auth token")
    void createRoom_shouldReturn403WithoutToken() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest();
        request.setRoomId("unauthorized-room");
        request.setLanguage("python");

        mockMvc.perform(post("/api/rooms/host")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/rooms/host - should fail with duplicate room ID")
    void createRoom_shouldReturn409WhenRoomExists() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest();
        request.setRoomId("duplicate-room");
        request.setLanguage("cpp");

        // Create first
        mockMvc.perform(post("/api/rooms/host")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Attempt duplicate
        mockMvc.perform(post("/api/rooms/host")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Room already exists")));
    }

    @Test
    @DisplayName("POST /api/rooms/host - should fail with invalid room ID")
    void createRoom_shouldReturn400WithInvalidRoomId() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest();
        request.setRoomId("a"); // too short (min 3)
        request.setLanguage("javascript");

        mockMvc.perform(post("/api/rooms/host")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.roomId", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/rooms/{roomId}/join - should join an existing room")
    void joinRoom_shouldReturn200WhenRoomExists() throws Exception {
        // Create room first
        CreateRoomRequest create = new CreateRoomRequest();
        create.setRoomId("joinable-room");
        create.setLanguage("python");

        mockMvc.perform(post("/api/rooms/host")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated());

        // Join it
        mockMvc.perform(post("/api/rooms/joinable-room/join")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId", is("joinable-room")))
                .andExpect(jsonPath("$.language", is("python")))
                .andExpect(jsonPath("$.role", notNullValue()))
                .andExpect(jsonPath("$.message", is("Joined room successfully")));
    }

    @Test
    @DisplayName("POST /api/rooms/{roomId}/join - should return 404 for non-existent room")
    void joinRoom_shouldReturn404WhenRoomNotFound() throws Exception {
        mockMvc.perform(post("/api/rooms/ghost-room/join")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("Room not found")));
    }
}
