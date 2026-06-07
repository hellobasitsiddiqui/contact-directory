package com.example.contacts.dto;

import com.example.contacts.model.AuditEvent;

import java.time.Instant;

/**
 * Response representation of a single audit-log entry returned to API clients.
 *
 * <p>This is an immutable view of an {@link AuditEvent} entity; the action is
 * exposed as its enum name string rather than the enum type.
 *
 * @param id         the unique identifier of the audit event
 * @param timestamp  the instant the audited action occurred
 * @param actor      the username of the principal who performed the action
 * @param action     the action that was performed, as its enum name
 * @param targetType the kind of target the action applied to
 * @param targetId   the id of the target, or {@code null} when not applicable
 * @param summary    a short human-readable description of the action
 */
public record AuditEventResponse(
        Long id,
        Instant timestamp,
        String actor,
        String action,
        String targetType,
        Long targetId,
        String summary) {

    /**
     * Maps an {@link AuditEvent} entity to an {@link AuditEventResponse} DTO,
     * copying every field and rendering the action as its enum name.
     *
     * @param e the audit event entity to map; must not be {@code null}
     * @return a fully populated {@link AuditEventResponse}
     */
    public static AuditEventResponse from(AuditEvent e) {
        return new AuditEventResponse(
                e.getId(),
                e.getTimestamp(),
                e.getActor(),
                e.getAction() != null ? e.getAction().name() : null,
                e.getTargetType(),
                e.getTargetId(),
                e.getSummary());
    }
}
