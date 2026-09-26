package com.collabeditor.realtime_editor.controller;

import com.collabeditor.realtime_editor.BaseIntegrationTest;
import com.collabeditor.realtime_editor.dto.request.LoginRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end check that {@code /api/auth/**} is throttled per client IP.
 * <p>
 * Runs with a cap of 10 per window so the 11th request must be rejected. Each test uses a
 * fresh client IP (and clears any matching Redis key first) so the assertions hold whether
 * the limiter is served by Redis or by the in-memory fallback, and regardless of counters
 * left behind by a previous run inside the same window.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "auth.rate-limit.capacity=10",
        "auth.rate-limit.refill-minutes=1"
})
class RateLimitIntegrationTest extends BaseIntegrationTest {

    private static final int CAPACITY = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("POST /api/auth/login - the 11th request from an IP returns 429")
    void login_shouldReturn429AfterCapIsReached() throws Exception {
        String ip = freshClientIp();

        // The first 10 are inside the budget. The credentials are bogus, so these are
        // rejected as 401 by the auth service - the point is that they are not throttled.
        for (int i = 1; i <= CAPACITY; i++) {
            mockMvc.perform(loginAttempt(ip))
                    .andExpect(status().isUnauthorized());
        }

        // The 11th exceeds the cap and is short-circuited by the rate limit filter.
        mockMvc.perform(loginAttempt(ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.error", is("Too Many Requests")))
                .andExpect(jsonPath("$.message", is("Too many attempts. Please try again later.")));
    }

    @Test
    @DisplayName("POST /api/auth/login - throttling one IP does not affect another")
    void login_shouldTrackLimitPerClientIp() throws Exception {
        String throttledIp = freshClientIp();
        String otherIp = freshClientIp();

        for (int i = 1; i <= CAPACITY; i++) {
            mockMvc.perform(loginAttempt(throttledIp))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(loginAttempt(throttledIp))
                .andExpect(status().isTooManyRequests());

        // A different caller still has its own budget.
        mockMvc.perform(loginAttempt(otherIp))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginAttempt(String ip)
            throws Exception {
        LoginRequest login = new LoginRequest();
        login.setUsername("ratelimit-" + UUID.randomUUID());
        login.setPassword("irrelevant-password");

        return post("/api/auth/login")
                .with(req -> { req.setRemoteAddr(ip); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login));
    }

    /** A client IP that has no prior state, in Redis or in memory. */
    private String freshClientIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // 203.0.113.0/24 is the reserved TEST-NET-3 documentation range.
        String ip = "203.0.113." + random.nextInt(1, 255);
        try {
            // Key layout mirrors RateLimiterService: ratelimit:<clientIp>.
            redisTemplate.delete("ratelimit:" + ip);
        } catch (RuntimeException e) {
            // Redis unavailable: the in-memory fallback starts empty anyway.
        }
        return ip;
    }
}