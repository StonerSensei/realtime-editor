package com.collabeditor.realtime_editor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        // 32+ character secret, 1 hour expiration
        jwtService = new JwtService("test-secret-key-for-unit-tests-minimum-32-characters-long", 3600000L);
    }

    @Test
    @DisplayName("Should generate a valid JWT token")
    void generateToken_shouldReturnNonEmptyToken() {
        String token = jwtService.generateToken("testuser");

        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    @DisplayName("Should extract username from token")
    void extractUsername_shouldReturnCorrectUsername() {
        String token = jwtService.generateToken("john_doe");

        String username = jwtService.extractUsername(token);

        assertEquals("john_doe", username);
    }

    @Test
    @DisplayName("Should validate a valid token")
    void isTokenValid_shouldReturnTrueForValidToken() {
        String token = jwtService.generateToken("testuser");

        assertTrue(jwtService.isTokenValid(token));
    }

    @Test
    @DisplayName("Should reject an expired token")
    void isTokenValid_shouldReturnFalseForExpiredToken() {
        // Create service with 0ms expiration (immediately expired)
        JwtService expiredService = new JwtService(
                "test-secret-key-for-unit-tests-minimum-32-characters-long", 0L);

        String token = expiredService.generateToken("testuser");

        assertFalse(expiredService.isTokenValid(token));
    }

    @Test
    @DisplayName("Should reject a tampered token")
    void isTokenValid_shouldReturnFalseForTamperedToken() {
        String token = jwtService.generateToken("testuser");
        String tampered = token + "tampered";

        assertFalse(jwtService.isTokenValid(tampered));
    }

    @Test
    @DisplayName("Should reject a completely invalid token")
    void isTokenValid_shouldReturnFalseForInvalidToken() {
        assertFalse(jwtService.isTokenValid("not.a.valid.token"));
    }

    @Test
    @DisplayName("Should reject null token")
    void isTokenValid_shouldReturnFalseForNullToken() {
        assertFalse(jwtService.isTokenValid(null));
    }

    @Test
    @DisplayName("Should generate different tokens for different users")
    void generateToken_shouldGenerateUniqueTokensPerUser() {
        String token1 = jwtService.generateToken("user1");
        String token2 = jwtService.generateToken("user2");

        assertNotEquals(token1, token2);
    }

    @Test
    @DisplayName("Token signed with different secret should be invalid")
    void isTokenValid_shouldRejectTokenFromDifferentSecret() {
        JwtService otherService = new JwtService(
                "another-secret-key-that-is-also-32-chars-minimum!", 3600000L);

        String token = otherService.generateToken("testuser");

        assertFalse(jwtService.isTokenValid(token));
    }
}
