package com.example.contacts.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity representing a single entry in the append-only audit log:
 * a record of who ({@code actor}) did what ({@code action}) to which target
 * ({@code targetType} / {@code targetId}) and when ({@code timestamp}).
 *
 * <p>Rows are write-once; the application never updates or deletes audit events.
 * The {@code timestamp} is set automatically on insert via {@link #onCreate()}
 * when it has not already been populated by the service.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The instant the audited action occurred. Set by the service when the event
     * is recorded, or backfilled on insert by {@link #onCreate()} if still null.
     */
    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    /**
     * Username of the principal who performed the action.
     */
    @Column(nullable = false)
    private String actor;

    /**
     * The action that was performed, stored as its enum name.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    /**
     * The kind of target the action applied to, e.g. {@code "CONTACT"},
     * {@code "USER"} or {@code "AUTH"}.
     */
    @Column(nullable = false)
    private String targetType;

    /**
     * Id of the specific target the action applied to, or {@code null} for
     * actions with no single target (e.g. bulk operations or imports).
     */
    @Column
    private Long targetId;

    /**
     * Short human-readable description of what happened.
     */
    @Column(length = 500)
    private String summary;

    /**
     * Creates an empty audit event. Required by JPA.
     */
    public AuditEvent() {
    }

    /**
     * Convenience constructor populating every business field of the event.
     *
     * @param timestamp  the instant the action occurred
     * @param actor      the username of the principal who acted
     * @param action     the action performed
     * @param targetType the kind of target acted upon
     * @param targetId   the id of the target, or {@code null} when not applicable
     * @param summary    a short human-readable description of the action
     */
    public AuditEvent(Instant timestamp, String actor, AuditAction action,
                      String targetType, Long targetId, String summary) {
        this.timestamp = timestamp;
        this.actor = actor;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.summary = summary;
    }

    /**
     * Backfills the timestamp before the event is first persisted if a caller
     * has not already set one.
     */
    @PrePersist
    protected void onCreate() {
        if (this.timestamp == null) {
            this.timestamp = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public AuditAction getAction() {
        return action;
    }

    public void setAction(AuditAction action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }
}
