package com.example.contacts.controller;

import com.example.contacts.dto.AuthResponse;
import com.example.contacts.dto.ChangePasswordRequest;
import com.example.contacts.dto.LoginRequest;
import com.example.contacts.dto.LogoutRequest;
import com.example.contacts.dto.RefreshRequest;
import com.example.contacts.dto.RegisterRequest;
import com.example.contacts.exception.DuplicateUsernameException;
import com.example.contacts.exception.InvalidCurrentPasswordException;
import com.example.contacts.exception.ResourceNotFoundException;
import com.example.contacts.model.AuditAction;
import com.example.contacts.model.Role;
import com.example.contacts.model.User;
import com.example.contacts.repository.UserRepository;
import com.example.contacts.security.JwtService;
import com.example.contacts.security.RefreshTokenService;
import com.example.contacts.service.AuditService;
import com.example.contacts.service.LoginAttemptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authentication endpoints: register an account, log in for a token pair,
 * refresh/rotate it, log out (server-side revocation), and read the current
 * principal. Access tokens are short-lived JWTs; refresh tokens are opaque
 * rotating secrets revocable server-side (CD-028).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Register, login, refresh, logout and current-user endpoints")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;
    private final AuditService auditService;

    public AuthController(AuthenticationManager authenticationManager,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          RefreshTokenService refreshTokenService,
                          LoginAttemptService loginAttemptService,
                          AuditService auditService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptService = loginAttemptService;
        this.auditService = auditService;
    }

    @Operation(summary = "Register a new account and receive an access + refresh token pair")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateUsernameException(
                    "Username already taken: " + request.username());
        }
        User user = new User(
                request.username(),
                passwordEncoder.encode(request.password()),
                Role.USER);
        userRepository.save(user);
        auditService.record(user.getUsername(), AuditAction.AUTH_REGISTER, "AUTH", user.getId(),
                "Registered account " + user.getUsername());

        return ResponseEntity.status(HttpStatus.CREATED).body(tokenPair(user));
    }

    @Operation(summary = "Log in with username/password and receive an access + refresh token pair")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        // Reject up front if the account is currently locked (-> 423 LOCKED).
        loginAttemptService.assertNotLocked(request.username());

        try {
            // Throws BadCredentialsException (-> 401) on invalid username/password.
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (BadCredentialsException ex) {
            // Count failures only for existing accounts; unknown usernames are
            // ignored so they cannot create lock state or leak account existence.
            loginAttemptService.recordFailure(request.username());
            throw ex;
        }

        // Successful login: clear any accumulated failure/lock state.
        loginAttemptService.recordSuccess(request.username());

        User user = userRepository.findByUsername(request.username()).orElseThrow();
        auditService.record(user.getUsername(), AuditAction.AUTH_LOGIN, "AUTH", user.getId(),
                "Logged in as " + user.getUsername());
        return ResponseEntity.ok(tokenPair(user));
    }

    @Operation(summary = "Exchange a refresh token for a new access + refresh token pair (rotation)")
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        // Rotation authenticates by the refresh secret itself (the access token
        // may already be expired). The service re-loads the user, so the new
        // access token carries the user's CURRENT role, not the login-time one.
        RefreshTokenService.IssuedToken issued = refreshTokenService.rotate(request.refreshToken());
        return ResponseEntity.ok(pairFor(issued));
    }

    @Operation(summary = "Log out: revoke the refresh-token session server-side (idempotent)")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest request) {
        // Always 204 — even for missing/unknown tokens — so logout is idempotent
        // and token validity is never leaked. The (short-lived) access token
        // simply ages out; server-side state to kill is the refresh family.
        String rawToken = request == null ? null : request.refreshToken();
        refreshTokenService.revokeByRawToken(rawToken).ifPresent(user ->
                auditService.record(user.getUsername(), AuditAction.AUTH_LOGOUT, "AUTH",
                        user.getId(), "Logged out (refresh session revoked)"));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Return the currently authenticated user")
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + authentication.getName()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", user.getUsername());
        body.put("role", user.getRole().name());
        body.put("createdAt", user.getCreatedAt());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Change the current user's own password (revokes every other session)")
    @PostMapping("/change-password")
    @Transactional
    public ResponseEntity<Map<String, Object>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + authentication.getName()));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new InvalidCurrentPasswordException("Current password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        // Kill every existing session on every device, then hand the caller a
        // fresh pair so THEIR session continues seamlessly (they just proved
        // the password, so re-authentication adds nothing).
        refreshTokenService.revokeAllForUser(user, RefreshTokenService.REASON_PASSWORD_CHANGE);
        auditService.record(user.getUsername(), AuditAction.AUTH_PASSWORD_CHANGE, "AUTH", user.getId(),
                "Changed own password");

        AuthResponse pair = tokenPair(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Password changed successfully");
        body.put("token", pair.token());
        body.put("tokenType", pair.tokenType());
        body.put("username", pair.username());
        body.put("role", pair.role());
        body.put("expiresInMs", pair.expiresInMs());
        body.put("refreshToken", pair.refreshToken());
        body.put("refreshExpiresInMs", pair.refreshExpiresInMs());
        return ResponseEntity.ok(body);
    }

    /** Issues a brand-new token pair (new refresh family) for the user. */
    private AuthResponse tokenPair(User user) {
        return pairFor(refreshTokenService.issue(user));
    }

    /** Builds the response for an issued/rotated refresh token + fresh access JWT. */
    private AuthResponse pairFor(RefreshTokenService.IssuedToken issued) {
        User user = issued.user();
        String accessToken = jwtService.generateToken(user.getUsername(), user.getRole().name());
        return AuthResponse.bearer(accessToken, user.getUsername(), user.getRole().name(),
                jwtService.getExpirationMs(), issued.rawToken(),
                refreshTokenService.getRefreshExpirationMs());
    }
}
