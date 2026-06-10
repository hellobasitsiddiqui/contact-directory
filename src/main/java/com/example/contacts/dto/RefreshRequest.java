package com.example.contacts.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/v1/auth/refresh} (CD-028).
 *
 * @param refreshToken the opaque refresh secret issued by login/register/refresh
 */
public record RefreshRequest(@NotBlank String refreshToken) {
}
