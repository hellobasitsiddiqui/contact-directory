package com.example.contacts.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralised translation of application exceptions into {@link ApiError}
 * responses with appropriate HTTP status codes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles missing resources, returning {@code 404 Not Found}.
     *
     * @param ex  the thrown exception
     * @param req the current request, used to populate the error path
     * @return a {@code 404} response wrapping an {@link ApiError}
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(
            ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    /**
     * Handles duplicate email conflicts, returning {@code 409 Conflict}.
     *
     * @param ex  the thrown exception
     * @param req the current request, used to populate the error path
     * @return a {@code 409} response wrapping an {@link ApiError}
     */
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiError> handleDuplicateEmail(
            DuplicateEmailException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    /**
     * Handles bean validation failures on request bodies, returning
     * {@code 400 Bad Request} with a field-to-message map.
     *
     * @param ex  the validation exception
     * @param req the current request, used to populate the error path
     * @return a {@code 400} response wrapping an {@link ApiError} with field errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ApiError body = new ApiError(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Validation failed",
                req.getRequestURI(),
                fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles invalid image uploads (missing file, unsupported type, or too
     * large per application validation), returning {@code 400 Bad Request}.
     *
     * @param ex  the thrown exception
     * @param req the current request, used to populate the error path
     * @return a {@code 400} response wrapping an {@link ApiError}
     */
    @ExceptionHandler(InvalidImageException.class)
    public ResponseEntity<ApiError> handleInvalidImage(
            InvalidImageException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    /**
     * Handles invalid uploaded files (e.g. a non-CSV or empty import file),
     * returning {@code 400 Bad Request}.
     *
     * @param ex  the thrown exception
     * @param req the current request, used to populate the error path
     * @return a {@code 400} response wrapping an {@link ApiError}
     */
    @ExceptionHandler(InvalidFileException.class)
    public ResponseEntity<ApiError> handleInvalidFile(
            InvalidFileException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    /**
     * Handles uploads exceeding the configured multipart size limit, returning
     * {@code 413 Payload Too Large}.
     *
     * @param ex  the thrown exception
     * @param req the current request, used to populate the error path
     * @return a {@code 413} response wrapping an {@link ApiError}
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE,
                "Uploaded file is too large (max 2MB)", req);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req) {
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                req.getRequestURI(),
                null);
        return ResponseEntity.status(status).body(body);
    }
}
