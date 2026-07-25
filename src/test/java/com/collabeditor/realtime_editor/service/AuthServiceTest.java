package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.dto.request.LoginRequest;
import com.collabeditor.realtime_editor.dto.request.RegisterRequest;
import com.collabeditor.realtime_editor.dto.response.AuthResponse;
import com.collabeditor.realtime_editor.exception.AuthenticationException;
import com.collabeditor.realtime_editor.model.User;
import com.collabeditor.realtime_editor.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        registerRequest = new RegisterRequest();
        registerRequest.setUsername("testuser");
        registerRequest.setEmail("test@example.com");
        registerRequest.setPassword("password123");

        loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("password123");
    }

    @Test
    @DisplayName("Should register a new user successfully")
    void register_shouldSucceedWithValidRequest() {
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded_password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateToken("testuser")).thenReturn("jwt-token-123");
        when(refreshTokenService.create("testuser")).thenReturn("refresh-123");

        AuthResponse response = authService.register(registerRequest);

        assertNotNull(response);
        assertEquals("testuser", response.getUsername());
        assertEquals("test@example.com", response.getEmail());
        assertEquals("jwt-token-123", response.getToken());
        assertEquals("refresh-123", response.getRefreshToken());
        assertEquals("Registration successful", response.getMessage());

        verify(userRepository).save(any(User.class));
        verify(passwordEncoder).encode("password123");
    }

    @Test
    @DisplayName("Should throw exception when username is already taken")
    void register_shouldThrowWhenUsernameExists() {
        when(userRepository.existsByUsername("testuser")).thenReturn(true);

        AuthenticationException exception = assertThrows(AuthenticationException.class,
                () -> authService.register(registerRequest));

        assertTrue(exception.getMessage().contains("Username already taken"));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw exception when email is already registered")
    void register_shouldThrowWhenEmailExists() {
        when(userRepository.existsByUsername("testuser")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        AuthenticationException exception = assertThrows(AuthenticationException.class,
                () -> authService.register(registerRequest));

        assertTrue(exception.getMessage().contains("Email already registered"));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should login successfully with valid credentials")
    void login_shouldSucceedWithValidCredentials() {
        User user = new User("testuser", "test@example.com", "encoded_password");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encoded_password")).thenReturn(true);
        when(jwtService.generateToken("testuser")).thenReturn("jwt-token-456");
        when(refreshTokenService.create("testuser")).thenReturn("refresh-456");

        AuthResponse response = authService.login(loginRequest);

        assertNotNull(response);
        assertEquals("testuser", response.getUsername());
        assertEquals("test@example.com", response.getEmail());
        assertEquals("jwt-token-456", response.getToken());
        assertEquals("refresh-456", response.getRefreshToken());
        assertEquals("Login successful", response.getMessage());
    }

    @Test
    @DisplayName("Should throw exception when username not found")
    void login_shouldThrowWhenUserNotFound() {
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());

        AuthenticationException exception = assertThrows(AuthenticationException.class,
                () -> authService.login(loginRequest));

        assertEquals("Invalid username or password", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw exception when password is wrong")
    void login_shouldThrowWhenPasswordWrong() {
        User user = new User("testuser", "test@example.com", "encoded_password");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encoded_password")).thenReturn(false);

        AuthenticationException exception = assertThrows(AuthenticationException.class,
                () -> authService.login(loginRequest));

        assertEquals("Invalid username or password", exception.getMessage());
        verify(jwtService, never()).generateToken(anyString());
    }
}
