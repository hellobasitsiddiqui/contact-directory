package com.example.contacts.exception;

/**
 * Thrown when a user changing their own password supplies a current password
 * that does not match the stored hash. Mapped to {@code 400 Bad Request}.
 */
public class InvalidCurrentPasswordException extends RuntimeException {

    public InvalidCurrentPasswordException(String message) {
        super(message);
    }
}
