package com.collabeditor.realtime_editor.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter for the authentication endpoints, shared across application instances.
 * <p>
 * Redis is the primary backend: a fixed window per client IP implemented as an atomic
 * {@code INCR} plus a {@code PEXPIRE} on first hit, so every instance decrements the same
 * budget. If Redis is unreachable the limiter degrades to a per-instance in-memory
 * Bucket4j bucket instead of failing open, so brute-force protection survives a Redis
 * outage (at reduced accuracy: each instance then tracks its own budget).
 * <p>
 * After a Redis error the limiter stops calling Redis for {@link #REDIS_COOLDOWN} to avoid
 * paying a connection timeout on every request while Redis is down.
 */
@Slf4j
@Service
public class RateLimiterService {

    /** Redis key prefix; the full key is {@code ratelimit:<clientIp>}. */
    static final String KEY_PREFIX = "ratelimit:";

    /** How long to skip Redis after a failure before probing it again. */
    static final Duration REDIS_COOLDOWN = Duration.ofSeconds(5);

    /**
     * Atomically counts one hit in the current window and, on the first hit, arms the
     * window expiry. Returns the running count so the caller can compare it to the cap.
     * KEYS[1] = rate limit key, ARGV[1] = window length in milliseconds.
     */
    private static final String FIXED_WINDOW_LUA =
            "local count = redis.call('INCR', KEYS[1]) "
            + "if count == 1 then "
            + "  redis.call('PEXPIRE', KEYS[1], ARGV[1]) "
            + "end "
            + "return count";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> fixedWindowScript;

    /** Per-instance fallback buckets, used only while Redis is unavailable. */
    private final Map<String, Bucket> fallbackBuckets = new ConcurrentHashMap<>();

    private final int capacity;
    private final Duration window;

    /** Epoch millis until which Redis is considered down; volatile for cross-thread visibility. */
    private volatile long redisDownUntil = 0L;

    public RateLimiterService(StringRedisTemplate redisTemplate,
                              @Value("${auth.rate-limit.capacity:10}") int capacity,
                              @Value("${auth.rate-limit.refill-minutes:1}") long windowMinutes) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
        this.window = Duration.ofMinutes(windowMinutes);
        this.fixedWindowScript = new DefaultRedisScript<>(FIXED_WINDOW_LUA, Long.class);
    }

    /**
     * Records a request for the given client and reports whether it may proceed.
     *
     * @param clientIp caller identity (client IP) the budget is tracked against
     * @return {@code true} if the request is within the limit, {@code false} if it should be rejected
     */
    public boolean allow(String clientIp) {
        if (!isRedisDegraded()) {
            Boolean allowed = tryRedis(clientIp);
            if (allowed != null) {
                return allowed;
            }
        }
        return allowInMemory(clientIp);
    }

    /**
     * @return the Redis verdict, or {@code null} if Redis could not answer and the caller
     *         should fall back to the in-memory limiter
     */
    private Boolean tryRedis(String clientIp) {
        try {
            Long count = redisTemplate.execute(fixedWindowScript,
                    List.of(KEY_PREFIX + clientIp),
                    Long.toString(window.toMillis()));

            if (count == null) {
                markRedisDown("script returned no value");
                return null;
            }
            return count <= capacity;
        } catch (RuntimeException e) {
            // Covers connection failures, timeouts and script errors.
            markRedisDown(e.getMessage());
            return null;
        }
    }

    /** In-memory Bucket4j fallback: same cap and window, but scoped to this instance. */
    private boolean allowInMemory(String clientIp) {
        return fallbackBuckets.computeIfAbsent(clientIp, k -> newBucket()).tryConsume(1);
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, window));
        return Bucket.builder().addLimit(limit).build();
    }

    private boolean isRedisDegraded() {
        return System.currentTimeMillis() < redisDownUntil;
    }

    private void markRedisDown(String reason) {
        redisDownUntil = System.currentTimeMillis() + REDIS_COOLDOWN.toMillis();
        log.warn("Redis rate limiting unavailable ({}); falling back to in-memory limiting for {}s",
                reason, REDIS_COOLDOWN.toSeconds());
    }
}
