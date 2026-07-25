package com.collabeditor.realtime_editor.service;

import com.collabeditor.realtime_editor.dto.request.LoginRequest;
import com.collabeditor.realtime_editor.dto.request.RegisterRequest;
import com.collabeditor.realtime_editor.dto.response.AuthResponse;
import com.collabeditor.realtime_editor.exception.AuthenticationException;
import com.collabeditor.realtime_editor.model.User;
import com.collabeditor.realtime_editor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new AuthenticationException("Username already taken: " + request.getUsername());
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AuthenticationException("Email already registered: " + request.getEmail());
        }

        String encodedPassword = passwordEncoder.encode(request.getPassword());
        User user = new User(request.getUsername(), request.getEmail(), encodedPassword);
        userRepository.save(user);

        log.info("User registered: {}", user.getUsername());

        return buildAuthResponse(user.getUsername(), user.getEmail(), "Registration successful");
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new AuthenticationException("Invalid username or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new AuthenticationException("Invalid username or password");
        }

        log.info("User logged in: {}", user.getUsername());

        return buildAuthResponse(user.getUsername(), user.getEmail(), "Login successful");
    }

    /** Exchanges a valid refresh token for a fresh access token (rotating the refresh token). */
    public AuthResponse refresh(String refreshToken) {
        String username = refreshTokenService.verifyAndGetUsername(refreshToken);

        String newAccessToken = jwtService.generateToken(username);
        String newRefreshToken = refreshTokenService.rotate(refreshToken, username);

        String email = userRepository.findByUsername(username).map(User::getEmail).orElse(null);
        log.debug("Access token refreshed for {}", username);

        return AuthResponse.builder()
                .token(newAccessToken)
                .refreshToken(newRefreshToken)
                .username(username)
                .email(email)
                .message("Token refreshed")
                .build();
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private AuthResponse buildAuthResponse(String username, String email, String message) {
        String accessToken = jwtService.generateToken(username);
        String refreshToken = refreshTokenService.create(username);

        return AuthResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .username(username)
                .email(email)
                .message(message)
                .build();
    }
}
