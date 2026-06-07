package com.example.contacts.dto;

/**
 * Successful authentication result returned by the login and register endpoints.
 *
 * @param token     the signed JWT bearer token
 * @param tokenType always {@code "Bearer"}
 * @param username  the authenticated username
 * @param role      the user's role (e.g. {@code USER}, {@code ADMIN})
 * @param expiresInMs token lifetime in milliseconds from issue
 */
public record AuthResponse(
        String token,
        String tokenType,
        String username,
        String role,
        long expiresInMs) {

    public static AuthResponse bearer(String token, String username, String role, long expiresInMs) {
        return new AuthResponse(token, "Bearer", username, role, expiresInMs);
    }
}
