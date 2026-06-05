package com.example.contacts.exception;

/**
 * Thrown when an uploaded image fails validation (missing file, unsupported
 * content type, or exceeding the allowed size). Translated to {@code 400 Bad
 * Request} by {@link GlobalExceptionHandler}.
 */
public class InvalidImageException extends RuntimeException {

    /**
     * Creates the exception with a human-readable validation message.
     *
     * @param message the detail message describing why the image was rejected
     */
    public InvalidImageException(String message) {
        super(message);
    }
}
