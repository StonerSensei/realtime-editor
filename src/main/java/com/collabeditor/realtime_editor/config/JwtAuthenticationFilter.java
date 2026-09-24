package com.collabeditor.realtime_editor.config;

import com.collabeditor.realtime_editor.service.JwtService;
import com.collabeditor.realtime_editor.service.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        if (jwtService.isTokenValid(token)) {
            // Revoked (logged-out) tokens are left unauthenticated, so protected endpoints
            // answer 401 through the security entry point.
            if (tokenBlacklistService.isBlacklisted(jwtService.extractJti(token))) {
                log.debug("Rejected blacklisted access token on {}", request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }

            String username = jwtService.extractUsername(token);

            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(username, null, List.of());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/auth/")
                || path.startsWith("/ws/")
                || path.startsWith("/yjs/")
                || path.equals("/")
                || path.equals("/index.html")
                || path.equals("/editor.html")
                || path.equals("/login.html")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/actuator/health")
                || path.equals("/actuator/info")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }
}