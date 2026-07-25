package com.collabeditor.realtime_editor.controller;

import com.collabeditor.realtime_editor.BaseIntegrationTest;
import com.collabeditor.realtime_editor.dto.request.LoginRequest;
import com.collabeditor.realtime_editor.dto.request.RegisterRequest;
import com.collabeditor.realtime_editor.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AuthControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/auth/register - should register a new user")
    void register_shouldReturn201WithToken() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setEmail("new@example.com");
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.username", is("newuser")))
                .andExpect(jsonPath("$.email", is("new@example.com")))
                .andExpect(jsonPath("$.message", is("Registration successful")));
    }

    @Test
    @DisplayName("POST /api/auth/register - should fail with duplicate username")
    void register_shouldReturn401WhenUsernameExists() throws Exception {
        // Register first user
        RegisterRequest first = new RegisterRequest();
        first.setUsername("duplicate");
        first.setEmail("first@example.com");
        first.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated());

        // Attempt duplicate username
        RegisterRequest second = new RegisterRequest();
        second.setUsername("duplicate");
        second.setEmail("second@example.com");
        second.setPassword("password456");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("Username already taken")));
    }

    @Test
    @DisplayName("POST /api/auth/register - should fail with invalid input")
    void register_shouldReturn400WithInvalidInput() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("ab"); // too short
        request.setEmail("not-an-email");
        request.setPassword("123"); // too short

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors", notNullValue()))
                .andExpect(jsonPath("$.validationErrors.username", notNullValue()))
                .andExpect(jsonPath("$.validationErrors.email", notNullValue()))
                .andExpect(jsonPath("$.validationErrors.password", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/auth/login - should login successfully")
    void login_shouldReturn200WithToken() throws Exception {
        // Register first
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("loginuser");
        reg.setEmail("login@example.com");
        reg.setPassword("mypassword");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        // Login
        LoginRequest login = new LoginRequest();
        login.setUsername("loginuser");
        login.setPassword("mypassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.username", is("loginuser")))
                .andExpect(jsonPath("$.message", is("Login successful")));
    }

    @Test
    @DisplayName("POST /api/auth/login - should fail with wrong password")
    void login_shouldReturn401WithWrongPassword() throws Exception {
        // Register first
        RegisterRequest reg = new RegisterRequest();
        reg.setUsername("wrongpw");
        reg.setEmail("wrong@example.com");
        reg.setPassword("correctpassword");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        // Login with wrong password
        LoginRequest login = new LoginRequest();
        login.setUsername("wrongpw");
        login.setPassword("incorrectpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Invalid username or password")));
    }

    @Test
    @DisplayName("POST /api/auth/login - should fail with non-existent user")
    void login_shouldReturn401WithUnknownUser() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setUsername("ghostuser");
        login.setPassword("password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Invalid username or password")));
    }
}
