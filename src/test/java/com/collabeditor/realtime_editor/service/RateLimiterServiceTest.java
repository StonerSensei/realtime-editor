package com.collabeditor.realtime_editor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    private static final int CAPACITY = 3;
    private static final long WINDOW_MINUTES = 1;
    private static final String IP = "203.0.113.7";

    @Mock
    private StringRedisTemplate redisTemplate;

    private RateLimiterService newService() {
        return new RateLimiterService(redisTemplate, CAPACITY, WINDOW_MINUTES);
    }

    @Test
    @DisplayName("allow - permits requests while the Redis count stays within the cap")
    void allow_shouldPermitWhenUnderLimit() {
        RateLimiterService service = newService();
        // Redis reports the running count; 1..CAPACITY are all within budget.
        doReturn(1L, 2L, 3L).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), anyString());

        for (int i = 1; i <= CAPACITY; i++) {
            assertThat(service.allow(IP))
                    .as("request %d of %d should be allowed", i, CAPACITY)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("allow - blocks once the Redis count exceeds the cap")
    void allow_shouldBlockWhenOverLimit() {
        RateLimiterService service = newService();
        // The (CAPACITY + 1)-th hit in the window must be rejected.
        doReturn((long) (CAPACITY + 1)).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), anyString());

        assertThat(service.allow(IP)).isFalse();
    }

    @Test
    @DisplayName("allow - keys the Redis counter as ratelimit:<ip> with the window in millis")
    void allow_shouldUseExpectedKeyAndWindowArgument() {
        RateLimiterService service = newService();
        doReturn(1L).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), anyString());

        service.allow(IP);

        verify(redisTemplate).execute(any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of("ratelimit:" + IP)),
                org.mockito.ArgumentMatchers.eq("60000"));
    }

    @Test
    @DisplayName("allow - falls back to in-memory limiting when Redis throws")
    void allow_shouldFallBackToInMemoryWhenRedisFails() {
        RateLimiterService service = newService();
        doThrow(new RedisConnectionFailureException("redis is down")).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), anyString());

        // The in-memory bucket takes over and still enforces the same cap.
        for (int i = 1; i <= CAPACITY; i++) {
            assertThat(service.allow(IP))
                    .as("fallback request %d of %d should be allowed", i, CAPACITY)
                    .isTrue();
        }
        assertThat(service.allow(IP))
                .as("fallback request beyond the cap should be blocked")
                .isFalse();

        // The degraded guard should stop hammering Redis during the cooldown, so the
        // failing call is not retried on every subsequent request.
        verify(redisTemplate, atMostOnce())
                .execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("allow - tracks each client IP independently in the fallback path")
    void allow_shouldTrackIpsSeparatelyInFallback() {
        RateLimiterService service = newService();
        doThrow(new RedisConnectionFailureException("redis is down")).when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), anyString());

        for (int i = 1; i <= CAPACITY; i++) {
            assertThat(service.allow(IP)).isTrue();
        }
        assertThat(service.allow(IP)).isFalse();

        // A different caller must still have its own full budget.
        assertThat(service.allow("198.51.100.42")).isTrue();
    }
}
