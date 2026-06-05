package com.example.contacts.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Standard error payload returned by the API for all handled exceptions.
 *
 * <p>Null-valued fields are omitted from the serialized JSON, so the
 * {@code errors} map is only present for validation failures.
 *
 * @param timestamp the moment the error occurred
 * @param status    the HTTP status code
 * @param error     the HTTP reason phrase (e.g. {@code "Not Found"})
 * @param message   a human-readable description of the error
 * @param path      the request URI that produced the error
 * @param errors    field-level validation messages, or {@code null} when not applicable
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> errors) {
}
