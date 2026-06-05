package com.example.contacts.exception;

/**
 * Thrown when an uploaded file (e.g. a CSV import) is missing, the wrong type,
 * or too large. Mapped to HTTP 400 by {@link GlobalExceptionHandler}.
 */
public class InvalidFileException extends RuntimeException {

    public InvalidFileException(String message) {
        super(message);
    }
}
