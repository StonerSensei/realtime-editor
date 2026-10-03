package com.collabeditor.realtime_editor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Global CORS configuration for the Spring Boot backend.
 *
 * WHY THIS IS NEEDED
 * ──────────────────
 * The Next.js frontend now runs on a different origin (e.g., http://localhost:3000)
 * from the Spring Boot backend (http://localhost:8080). Browsers enforce the Same-Origin
 * Policy: any fetch/XHR from origin A to origin B triggers a CORS preflight request
 * (OPTIONS). Without this bean the Spring Security filter chain blocks those preflight
 * requests with a 403, and every API call from the frontend fails.
 *
 * HOW IT WORKS
 * ────────────
 * 1. The browser sends OPTIONS /api/auth/login with:
 *      Origin: http://localhost:3000
 *      Access-Control-Request-Method: POST
 * 2. Spring Security intercepts it and delegates to this CorsConfigurationSource.
 * 3. We reply with the appropriate Access-Control-Allow-* headers.
 * 4. The browser is now happy to send the real POST.
 *
 * WEBSOCKETS
 * ──────────
 * WebSocket upgrade requests also go through the HTTP layer for the initial handshake.
 * However, the existing WebSocketConfig already uses .setAllowedOrigins("*"), so
 * WebSocket connections are already open and do NOT need changes here.
 *
 * WHAT YOU NEED TO CHANGE IN PRODUCTION
 * ──────────────────────────────────────
 * Set the CORS_ALLOWED_ORIGINS env var to your real frontend URL, e.g.:
 *   CORS_ALLOWED_ORIGINS=https://collabide.your-domain.com
 *
 * Multiple origins are comma-separated:
 *   CORS_ALLOWED_ORIGINS=https://app.example.com,https://staging.example.com
 *
 * If frontend and backend share the same domain (e.g., via an Nginx reverse proxy
 * that routes /api/* to Spring Boot and everything else to Next.js), you don't need
 * CORS at all — both apps look like the same origin to the browser. In that case you
 * can leave this config in place with an empty allowed-origins and it'll be a no-op.
 */
@Configuration
public class CorsConfig {

    // Set CORS_ALLOWED_ORIGINS env var in production.
    // Comma-separated list of allowed frontend origins.
    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOriginsProperty;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Parse comma-separated origins
        List<String> origins = Arrays.stream(allowedOriginsProperty.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        // Use setAllowedOriginPatterns (supports wildcards) instead of setAllowedOrigins
        // when allowCredentials is true — plain setAllowedOrigins("*") + credentials = error.
        config.setAllowedOriginPatterns(origins);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);   // required for JWT in Authorization header
        config.setMaxAge(3600L);            // cache preflight for 1 hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
