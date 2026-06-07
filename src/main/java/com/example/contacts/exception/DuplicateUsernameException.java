package com.example.contacts.exception;

/**
 * Thrown when registering a username that is already taken. Mapped to
 * {@code 409 Conflict}.
 */
public class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException(String message) {
        super(message);
    }
}
