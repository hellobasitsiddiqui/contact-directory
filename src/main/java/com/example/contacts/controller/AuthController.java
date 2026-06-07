package com.example.contacts.controller;

import com.example.contacts.dto.AuthResponse;
import com.example.contacts.dto.ChangePasswordRequest;
import com.example.contacts.dto.LoginRequest;
import com.example.contacts.dto.RegisterRequest;
import com.example.contacts.exception.DuplicateUsernameException;
import com.example.contacts.exception.InvalidCurrentPasswordException;
import com.example.contacts.exception.ResourceNotFoundException;
import com.example.contacts.model.AuditAction;
import com.example.contacts.model.Role;
import com.example.contacts.model.User;
import com.example.contacts.repository.UserRepository;
import com.example.contacts.security.JwtService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authentication endpoints: register an account, log in for a JWT, and read the
 * current principal. All other endpoints require a bearer token issued here.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Register, login and current-user endpoints")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final AuditService auditService;

    public AuthController(AuthenticationManager authenticationManager,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          LoginAttemptService loginAttemptService,
                          AuditService auditService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.auditService = auditService;
    }

    @Operation(summary = "Register a new account and receive a JWT")
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

        String token = jwtService.generateToken(user.getUsername(), user.getRole().name());
        AuthResponse body = AuthResponse.bearer(
                token, user.getUsername(), user.getRole().name(), jwtService.getExpirationMs());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(summary = "Log in with username/password and receive a JWT")
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
        String token = jwtService.generateToken(user.getUsername(), user.getRole().name());
        AuthResponse body = AuthResponse.bearer(
                token, user.getUsername(), user.getRole().name(), jwtService.getExpirationMs());
        return ResponseEntity.ok(body);
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

    @Operation(summary = "Change the current user's own password")
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + authentication.getName()));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new InvalidCurrentPasswordException("Current password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        auditService.record(user.getUsername(), AuditAction.AUTH_PASSWORD_CHANGE, "AUTH", user.getId(),
                "Changed own password");
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }
}
