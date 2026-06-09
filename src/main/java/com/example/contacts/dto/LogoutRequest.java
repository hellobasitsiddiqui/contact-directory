package com.example.contacts.dto;

/**
 * Body of {@code POST /api/v1/auth/logout} (CD-028). Deliberately unvalidated:
 * logout is idempotent and always answers {@code 204}, even for a missing or
 * unknown token, so token validity is never leaked.
 *
 * @param refreshToken the refresh secret to revoke (may be {@code null})
 */
public record LogoutRequest(String refreshToken) {
}
