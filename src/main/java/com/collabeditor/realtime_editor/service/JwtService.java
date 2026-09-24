package com.collabeditor.realtime_editor.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret:collabide-default-secret-key-change-in-production-min-32-chars}") String secret,
            @Value("${jwt.access-expiration-ms:1800000}") long expirationMs) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /**
     * Issues a signed access token. Every token carries a random {@code jti} (JWT ID) so an
     * individual token can be revoked before it expires via {@link TokenBlacklistService}.
     */
    public String generateToken(String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    /**
     * Returns the token's {@code jti} claim. Tokens issued before the jti was introduced
     * return {@code null}; they cannot be blacklisted but still expire normally.
     */
    public String extractJti(String token) {
        return extractClaims(token).getId();
    }

    /**
     * Milliseconds until the token expires, or {@code 0} if it is already expired or cannot
     * be parsed. Used as the blacklist TTL so revoked entries disappear once the token would
     * have expired anyway.
     */
    public long getRemainingValidityMs(String token) {
        try {
            Date expiration = extractClaims(token).getExpiration();
            if (expiration == null) return 0L;
            return Math.max(0L, expiration.getTime() - System.currentTimeMillis());
        } catch (JwtException | IllegalArgumentException e) {
            return 0L;
        }
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}