package com.collabeditor.realtime_editor.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A long-lived refresh token stored server-side so it can be revoked (unlike the
 * short-lived stateless access JWT). Exchanged at {@code /api/auth/refresh} for a
 * fresh access token.
 */
@Document("refresh_tokens")
@Data
@NoArgsConstructor
public class RefreshToken {

    @Id
    private String id;

    @Indexed(unique = true)
    private String token;

    @Indexed
    private String username;

    /** TTL index: MongoDB automatically deletes the document once this instant passes. */
    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt;

    private boolean revoked;

    public RefreshToken(String token, String username, Instant expiresAt) {
        this.token = token;
        this.username = username;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }
}
