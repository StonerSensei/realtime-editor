package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.exception.AuthenticationException;
import com.collabeditor.realtime_editor.model.RefreshToken;
import com.collabeditor.realtime_editor.repository.RefreshTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Issues, validates, rotates, and revokes refresh tokens. Tokens are opaque random
 * strings persisted in MongoDB so they can be individually revoked (on logout) or
 * expired - something a stateless JWT cannot do.
 */
@Slf4j
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final long validityMs;

    public RefreshTokenService(
            RefreshTokenRepository repository,
            @Value("${jwt.refresh-expiration-ms:604800000}") long validityMs) {
        this.repository = repository;
        this.validityMs = validityMs;
    }

    public String create(String username) {
        RefreshToken token = new RefreshToken(
                UUID.randomUUID().toString(),
                username,
                Instant.now().plusMillis(validityMs));
        repository.save(token);
        return token.getToken();
    }

    /** Validates a refresh token and returns the owning username, or throws. */
    public String verifyAndGetUsername(String token) {
        RefreshToken stored = repository.findByToken(token)
                .orElseThrow(() -> new AuthenticationException("Invalid refresh token"));

        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new AuthenticationException("Refresh token expired or revoked");
        }
        return stored.getUsername();
    }

    public void revoke(String token) {
        repository.findByToken(token).ifPresent(stored -> {
            stored.setRevoked(true);
            repository.save(stored);
            log.debug("Revoked refresh token for {}", stored.getUsername());
        });
    }

    /** Revokes the old token and issues a new one (rotation on every refresh). */
    public String rotate(String oldToken, String username) {
        revoke(oldToken);
        return create(username);
    }
}
