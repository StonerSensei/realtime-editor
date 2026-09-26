package com.collabeditor.realtime_editor.config;

import com.collabeditor.realtime_editor.service.RateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rate limits the authentication endpoints ({@code /api/auth/**}) per client IP.
 * <p>
 * The accounting itself lives in {@link RateLimiterService}, which is Redis-backed so the
 * budget is shared across instances and falls back to in-memory counting if Redis is down.
 * When the limit is exceeded this filter short-circuits with HTTP 429, throttling
 * brute-force login and registration attempts.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterService rateLimiterService;

    public RateLimitFilter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String clientIp = clientIp(request);

        if (rateLimiterService.allow(clientIp)) {
            filterChain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded for {} on {}", clientIp, request.getRequestURI());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\","
                    + "\"message\":\"Too many attempts. Please try again later.\"}");
        }
    }

    /** Only rate-limit the auth endpoints. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/auth/");
    }

    /**
     * Returns the client's IP from the TCP connection. {@code X-Forwarded-For} is deliberately
     * ignored: it can be set to any value by the client, which lets an attacker bypass the
     * rate limit by sending a different fake IP on every request. If the app is deployed
     * behind a trusted reverse proxy, configure Spring's {@code ForwardedHeaderFilter} with
     * a proxy allowlist instead — that rewrites {@code getRemoteAddr()} safely.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}