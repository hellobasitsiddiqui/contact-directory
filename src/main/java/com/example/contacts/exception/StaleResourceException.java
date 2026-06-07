package com.example.contacts.exception;

/**
 * Thrown when an update or patch is attempted against a contact whose version
 * no longer matches the version supplied by the client, indicating the resource
 * was modified concurrently by someone else.
 *
 * <p>Mapped to an HTTP {@code 412 Precondition Failed} response by
 * {@link GlobalExceptionHandler}.
 */
public class StaleResourceException extends RuntimeException {

    /**
     * Creates a new {@code StaleResourceException}.
     *
     * @param message a human-readable description of the optimistic-locking conflict
     */
    public StaleResourceException(String message) {
        super(message);
    }
}
