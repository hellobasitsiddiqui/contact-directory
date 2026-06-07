package com.example.contacts.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Credentials submitted to {@code POST /api/v1/auth/login}.
 */
public record LoginRequest(
        @NotBlank(message = "Username is required") String username,
        @NotBlank(message = "Password is required") String password) {
}
