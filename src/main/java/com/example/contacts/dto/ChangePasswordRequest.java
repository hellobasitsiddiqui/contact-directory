package com.example.contacts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for an authenticated user changing their own password via
 * {@code POST /api/v1/auth/change-password}. The {@code currentPassword} is
 * verified against the stored hash before the {@code newPassword} is applied.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        String currentPassword,

        @NotBlank(message = "New password is required")
        @Size(min = 6, max = 100, message = "New password must be between 6 and 100 characters")
        String newPassword) {
}
