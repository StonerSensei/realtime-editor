package com.collabeditor.realtime_editor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    private static final String JTI = "3f2b8c1e-0000-4000-8000-000000000001";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private MutableClock clock;
    private TokenBlacklistService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        service = new TokenBlacklistService(redisTemplate, clock, 100);
    }

    // ── Redis path ────────────────────────────────

    @Test
    @DisplayName("blacklist - writes blacklist:<jti> = \"1\" to Redis with the given TTL")
    void blacklist_shouldWriteRedisKeyWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.blacklist(JTI, 60_000L);

        verify(valueOps).set("blacklist:" + JTI, "1", Duration.ofMillis(60_000L));
    }

    @Test
    @DisplayName("isBlacklisted - a local hit answers without calling Redis")
    void isBlacklisted_shouldShortCircuitOnLocalHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service.blacklist(JTI, 60_000L);

        assertThat(service.isBlacklisted(JTI)).isTrue();
        verify(redisTemplate, never()).hasKey(anyString());
    }

    @Test
    @DisplayName("isBlacklisted - consults Redis for tokens revoked on another instance")
    void isBlacklisted_shouldFindTokenRevokedElsewhere() {
        when(redisTemplate.hasKey("blacklist:" + JTI)).thenReturn(true);

        assertThat(service.isBlacklisted(JTI)).isTrue();
    }

    @Test
    @DisplayName("isBlacklisted - false when neither the local map nor Redis has the jti")
    void isBlacklisted_shouldBeFalseForUnknownJti() {
        when(redisTemplate.hasKey("blacklist:" + JTI)).thenReturn(false);

        assertThat(service.isBlacklisted(JTI)).isFalse();
    }

    // ── Fallback / degraded mode ──────────────────

    @Test
    @DisplayName("blacklist - still blocks locally when the Redis write fails")
    void blacklist_shouldFallBackToLocalWhenRedisWriteFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new RedisConnectionFailureException("redis is down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        service.blacklist(JTI, 60_000L);

        assertThat(service.isBlacklisted(JTI)).isTrue();
    }

    @Test
    @DisplayName("isBlacklisted - fails open (false) when Redis throws and the jti isn't local")
    void isBlacklisted_shouldFailOpenWhenRedisReadFails() {
        doThrow(new RedisConnectionFailureException("redis is down"))
                .when(redisTemplate).hasKey(anyString());

        assertThat(service.isBlacklisted(JTI)).isFalse();
    }

    @Test
    @DisplayName("After a Redis error, Redis is skipped during the cooldown")
    void redisError_shouldTriggerCooldown() {
        doThrow(new RedisConnectionFailureException("redis is down"))
                .when(redisTemplate).hasKey(anyString());

        service.isBlacklisted(JTI);
        service.isBlacklisted(JTI);
        service.isBlacklisted(JTI);

        verify(redisTemplate, atMostOnce()).hasKey(anyString());
    }

    @Test
    @DisplayName("After the cooldown elapses, Redis is probed again")
    void redisCooldown_shouldExpire() {
        when(redisTemplate.hasKey("blacklist:" + JTI))
                .thenThrow(new RedisConnectionFailureException("redis is down"))
                .thenReturn(true);

        assertThat(service.isBlacklisted(JTI)).isFalse();          // error -> cooldown
        clock.advance(TokenBlacklistService.REDIS_COOLDOWN.plusMillis(1));

        assertThat(service.isBlacklisted(JTI)).isTrue();           // Redis consulted again
    }

    // ── Local map: TTL + bound ────────────────────

    @Test
    @DisplayName("Local entries expire with the token's TTL (lazy eviction)")
    void localEntry_shouldExpireAfterTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey("blacklist:" + JTI)).thenReturn(false); // Redis TTL expired too

        service.blacklist(JTI, 1_000L);
        assertThat(service.isBlacklisted(JTI)).isTrue();

        clock.advance(Duration.ofMillis(1_001));

        assertThat(service.isBlacklisted(JTI)).isFalse();
        assertThat(service.localSize()).isZero();
    }

    @Test
    @DisplayName("The local map never grows beyond its bound")
    void localMap_shouldStayBounded() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        TokenBlacklistService small = new TokenBlacklistService(redisTemplate, clock, 3);

        for (int i = 0; i < 10; i++) {
            small.blacklist("jti-" + i, 60_000L + i);
        }

        assertThat(small.localSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("When full, expired entries are evicted before live ones")
    void localMap_shouldEvictExpiredFirst() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        TokenBlacklistService small = new TokenBlacklistService(redisTemplate, clock, 2);

        small.blacklist("short-lived", 1_000L);
        small.blacklist("long-lived", 600_000L);
        clock.advance(Duration.ofMillis(2_000));                   // short-lived has expired

        small.blacklist("newest", 600_000L);                        // map is full -> evict

        assertThat(small.isBlacklisted("long-lived")).isTrue();
        assertThat(small.isBlacklisted("newest")).isTrue();
        assertThat(small.isBlacklisted("short-lived")).isFalse();
    }

    // ── Input guards ──────────────────────────────

    @Test
    @DisplayName("Null/blank jti or non-positive TTL is a no-op and never blacklisted")
    void invalidInput_shouldBeIgnored() {
        service.blacklist(null, 60_000L);
        service.blacklist("  ", 60_000L);
        service.blacklist(JTI, 0L);
        service.blacklist(JTI, -5L);

        assertThat(service.isBlacklisted(null)).isFalse();
        assertThat(service.isBlacklisted("")).isFalse();
        assertThat(service.localSize()).isZero();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("A zero-TTL blacklist of a real jti does not write to Redis")
    void zeroTtl_shouldNotWriteRedis() {
        service.blacklist(JTI, 0L);

        verify(redisTemplate, never()).opsForValue();
        verify(valueOps, never()).set(eq("blacklist:" + JTI), anyString(), any(Duration.class));
    }

    /** A Clock whose time only moves when the test says so. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}