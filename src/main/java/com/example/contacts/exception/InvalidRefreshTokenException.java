package com.example.contacts.exception;

/**
 * Thrown when a presented refresh token is unusable for any reason — unknown,
 * expired, revoked, reused, or belonging to a disabled account. Mapped to a
 * generic {@code 401} with one fixed message so callers cannot probe token
 * state (CD-028).
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Invalid refresh token");
    }
}
