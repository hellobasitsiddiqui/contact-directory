package com.example.contacts.dto;

/**
 * Successful authentication result returned by the login, register and refresh
 * endpoints.
 *
 * @param token     the signed JWT bearer token (short-lived access token)
 * @param tokenType always {@code "Bearer"}
 * @param username  the authenticated username
 * @param role      the user's role (e.g. {@code USER}, {@code ADMIN})
 * @param expiresInMs access-token lifetime in milliseconds from issue
 * @param refreshToken the opaque rotating refresh secret (CD-028); present it to
 *                     {@code POST /auth/refresh} for a new pair
 * @param refreshExpiresInMs refresh-token lifetime in milliseconds from issue
 */
public record AuthResponse(
        String token,
        String tokenType,
        String username,
        String role,
        long expiresInMs,
        String refreshToken,
        long refreshExpiresInMs) {

    public static AuthResponse bearer(String token, String username, String role, long expiresInMs,
                                      String refreshToken, long refreshExpiresInMs) {
        return new AuthResponse(token, "Bearer", username, role, expiresInMs,
                refreshToken, refreshExpiresInMs);
    }
}
