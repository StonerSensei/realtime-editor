package com.collabeditor.realtime_editor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Revokes individual access tokens (by their {@code jti}) before they naturally expire,
 * e.g. on logout. Stateless JWTs cannot otherwise be invalidated.
 * <p>
 * Storage is two-tiered:
 * <ul>
 *   <li><b>Redis</b> ({@code blacklist:<jti>} = "1", TTL = the token's remaining validity) is
 *       shared by every instance, so a logout on one node is honoured by all of them.</li>
 *   <li><b>A bounded in-memory map</b> (jti -> expiry epoch millis) on this instance. It is
 *       always written, so a token revoked here stays revoked here even if Redis is down,
 *       and it is checked first so the common "not revoked locally" path costs one map read
 *       before Redis is consulted. Expired entries are evicted lazily.</li>
 * </ul>
 * Lookups <b>fail open</b>: if Redis cannot answer, a token not in the local map is treated
 * as not blacklisted. That keeps the whole API available during a Redis outage, at the cost
 * that a logout performed on a <i>different</i> instance is not seen here until Redis
 * returns (or the token expires, at most one access-token lifetime).
 * <p>
 * Like {@link RateLimiterService}, after a Redis error the service skips Redis for
 * {@link #REDIS_COOLDOWN} so a Redis outage does not add a connection timeout to every
 * authenticated request.
 */
@Slf4j
@Service
public class TokenBlacklistService {

    /** Redis key prefix; the full key is {@code blacklist:<jti>}. */
    static final String KEY_PREFIX = "blacklist:";

    /** Upper bound on locally tracked revocations, to cap memory use. */
    static final int DEFAULT_MAX_LOCAL_ENTRIES = 10_000;

    /** How long to skip Redis after a failure before probing it again. */
    static final Duration REDIS_COOLDOWN = Duration.ofSeconds(5);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final int maxLocalEntries;

    /** jti -> epoch millis at which the entry (and the token) expires. */
    private final Map<String, Long> localBlacklist = new ConcurrentHashMap<>();

    /** Epoch millis until which Redis is considered down; volatile for cross-thread visibility. */
    private volatile long redisDownUntil = 0L;

    @Autowired
    public TokenBlacklistService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Clock.systemUTC(), DEFAULT_MAX_LOCAL_ENTRIES);
    }

    /** Test seam: lets tests control time and the local capacity. */
    TokenBlacklistService(StringRedisTemplate redisTemplate, Clock clock, int maxLocalEntries) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.maxLocalEntries = maxLocalEntries;
    }

    /**
     * Blacklists a token id for {@code ttlMs} milliseconds (normally the token's remaining
     * validity). Writes both the local map and Redis; a Redis failure is logged, not thrown,
     * so logout still succeeds.
     */
    public void blacklist(String jti, long ttlMs) {
        if (jti == null || jti.isBlank() || ttlMs <= 0) {
            return; // nothing to revoke, or the token has already expired
        }

        putLocal(jti, now() + ttlMs);

        if (isRedisDegraded()) {
            log.warn("Redis unavailable; token {} blacklisted on this instance only", jti);
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofMillis(ttlMs));
        } catch (RuntimeException e) {
            markRedisDown(e.getMessage());
            log.warn("Token {} blacklisted on this instance only (Redis write failed)", jti);
        }
    }

    /**
     * @return {@code true} if the token id has been revoked. Checks the local map first, then
     *         Redis; returns {@code false} if Redis cannot answer (fail-open).
     */
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }

        if (isLocallyBlacklisted(jti)) {
            return true;
        }

        if (isRedisDegraded()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (RuntimeException e) {
            markRedisDown(e.getMessage());
            return false;
        }
    }

    // ── Local map ─────────────────────────────────

    private boolean isLocallyBlacklisted(String jti) {
        Long expiresAt = localBlacklist.get(jti);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt <= now()) {
            localBlacklist.remove(jti, expiresAt); // lazy eviction
            return false;
        }
        return true;
    }

    private void putLocal(String jti, long expiresAt) {
        if (localBlacklist.size() >= maxLocalEntries && !localBlacklist.containsKey(jti)) {
            evictExpired();
            if (localBlacklist.size() >= maxLocalEntries) {
                evictSoonestToExpire();
            }
        }
        localBlacklist.put(jti, expiresAt);
    }

    private void evictExpired() {
        long now = now();
        localBlacklist.entrySet().removeIf(e -> e.getValue() <= now);
    }

    /**
     * The map is full of live entries: drop the one closest to expiry, since it would have
     * been harmless soonest. That token remains revoked in Redis if Redis is up.
     */
    private void evictSoonestToExpire() {
        localBlacklist.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .ifPresent(e -> localBlacklist.remove(e.getKey(), e.getValue()));
    }

    /** Visible for tests. */
    int localSize() {
        return localBlacklist.size();
    }

    // ── Redis degraded guard ──────────────────────

    private boolean isRedisDegraded() {
        return now() < redisDownUntil;
    }

    private void markRedisDown(String reason) {
        redisDownUntil = now() + REDIS_COOLDOWN.toMillis();
        log.warn("Redis token blacklist unavailable ({}); using local blacklist only for {}s",
                reason, REDIS_COOLDOWN.toSeconds());
    }

    private long now() {
        return clock.millis();
    }
}