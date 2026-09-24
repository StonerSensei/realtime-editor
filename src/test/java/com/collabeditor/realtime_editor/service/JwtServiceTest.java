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

    // ── jti + remaining validity (token blacklist support) ──

    @Test
    @DisplayName("Generated tokens carry a non-blank jti")
    void generateToken_shouldIncludeJti() {
        String token = jwtService.generateToken("testuser");

        String jti = jwtService.extractJti(token);

        assertNotNull(jti);
        assertFalse(jti.isBlank());
    }

    @Test
    @DisplayName("Each token gets a unique jti, even for the same user")
    void generateToken_shouldUseUniqueJtiPerToken() {
        String first = jwtService.extractJti(jwtService.generateToken("testuser"));
        String second = jwtService.extractJti(jwtService.generateToken("testuser"));

        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("Remaining validity is positive and bounded by the configured lifetime")
    void getRemainingValidityMs_shouldBeWithinLifetime() {
        String token = jwtService.generateToken("testuser");

        long remaining = jwtService.getRemainingValidityMs(token);

        assertTrue(remaining > 0, "fresh token should have time left");
        assertTrue(remaining <= 3600000L, "cannot exceed the 1h lifetime");
    }

    @Test
    @DisplayName("Remaining validity is 0 for expired or invalid tokens")
    void getRemainingValidityMs_shouldBeZeroForExpiredOrInvalid() {
        JwtService expiredService = new JwtService(
                "test-secret-key-for-unit-tests-minimum-32-characters-long", 0L);

        assertEquals(0L, expiredService.getRemainingValidityMs(expiredService.generateToken("u")));
        assertEquals(0L, jwtService.getRemainingValidityMs("not.a.valid.token"));
        assertEquals(0L, jwtService.getRemainingValidityMs(null));
    }
}