package com.example.contacts.exception;

/**
 * Thrown when an otherwise-valid operation is refused to protect system
 * integrity — e.g. an admin trying to demote, disable or delete their own
 * account, or removing the last remaining admin. Mapped to {@code 409 Conflict}.
 */
public class OperationNotAllowedException extends RuntimeException {

    public OperationNotAllowedException(String message) {
        super(message);
    }
}
