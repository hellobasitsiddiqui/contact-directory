package com.example.contacts.exception;

/**
 * Thrown when attempting to persist a contact whose email already exists.
 *
 * <p>Mapped to an HTTP {@code 409 Conflict} response by
 * {@link GlobalExceptionHandler}.
 */
public class DuplicateEmailException extends RuntimeException {

    /**
     * Creates a new {@code DuplicateEmailException}.
     *
     * @param message a human-readable description of the conflict
     */
    public DuplicateEmailException(String message) {
        super(message);
    }
}
