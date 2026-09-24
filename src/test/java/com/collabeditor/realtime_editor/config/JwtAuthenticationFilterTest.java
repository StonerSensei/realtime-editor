package com.collabeditor.realtime_editor.config;

import com.collabeditor.realtime_editor.service.JwtService;
import com.collabeditor.realtime_editor.service.TokenBlacklistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JwtAuthenticationFilter}: a real {@link JwtService} signs tokens and a
 * mocked {@link TokenBlacklistService} decides revocation.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-key-for-unit-tests-minimum-32-characters-long";

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    private JwtService jwtService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtService = new JwtService(SECRET, 3_600_000L);
        filter = new JwtAuthenticationFilter(jwtService, tokenBlacklistService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("A valid, non-blacklisted token authenticates the request")
    void validToken_shouldAuthenticate() throws Exception {
        String token = jwtService.generateToken("alice");
        when(tokenBlacklistService.isBlacklisted(jwtService.extractJti(token))).thenReturn(false);

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(protectedRequest(token), new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("alice");
        assertThat(chain.getRequest()).as("chain continues").isNotNull();
    }

    @Test
    @DisplayName("A blacklisted token leaves the request unauthenticated")
    void blacklistedToken_shouldNotAuthenticate() throws Exception {
        String token = jwtService.generateToken("alice");
        when(tokenBlacklistService.isBlacklisted(jwtService.extractJti(token))).thenReturn(true);

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(protectedRequest(token), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // The chain still runs; Spring Security's entry point turns this into a 401.
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("An invalid token is rejected without consulting the blacklist")
    void invalidToken_shouldSkipBlacklistLookup() throws Exception {
        filter.doFilter(protectedRequest("not.a.jwt"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(tokenBlacklistService);
    }

    @Test
    @DisplayName("Requests without a Bearer header are passed through untouched")
    void noHeader_shouldPassThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rooms/x");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(tokenBlacklistService);
    }

    private MockHttpServletRequest protectedRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rooms/x");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}