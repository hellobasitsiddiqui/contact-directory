package com.example.contacts.exception;

/**
 * Thrown when a requested resource (e.g. a contact) cannot be found.
 *
 * <p>Mapped to an HTTP {@code 404 Not Found} response by
 * {@link GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * Creates a new {@code ResourceNotFoundException}.
     *
     * @param message a human-readable description of the missing resource
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
