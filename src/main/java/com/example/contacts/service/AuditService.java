package com.example.contacts.service;

import com.example.contacts.dto.AuditEventResponse;
import com.example.contacts.model.AuditAction;
import com.example.contacts.repository.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the append-only audit log: it records mutating
 * actions and exposes a filtered, paginated read of the history.
 *
 * <p>Recording is deliberately resilient: an audit write must never break the
 * caller it is observing. {@link #record} is intentionally <em>not</em>
 * transactional; it delegates the persistence to {@link AuditRecorder}, which
 * runs in its own {@code REQUIRES_NEW} transaction. A failure there rolls back
 * only that isolated transaction and is then caught and logged here, so it can
 * never mark a surrounding transaction rollback-only nor surface as an error to
 * the caller. The read path runs in a read-only transaction and maps entities
 * to {@link AuditEventResponse} so the persistence model never leaks beyond this
 * layer.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;
    private final AuditRecorder recorder;

    /**
     * Creates a new service backed by the given repository and recorder.
     *
     * @param repository the audit-event repository (constructor injected)
     * @param recorder   persists events in an isolated {@code REQUIRES_NEW} transaction
     */
    public AuditService(AuditEventRepository repository, AuditRecorder recorder) {
        this.repository = repository;
        this.recorder = recorder;
    }

    /**
     * Records a single audit event, stamping it with the current instant.
     *
     * <p>The persistence runs in an isolated transaction via {@link AuditRecorder};
     * any failure (including one surfaced at flush/commit) is logged and swallowed
     * here so that an audit problem can never interfere with — or roll back — the
     * operation being audited.
     *
     * @param actor      the username of the principal who performed the action
     * @param action     the action that was performed
     * @param targetType the kind of target acted upon (e.g. {@code "CONTACT"})
     * @param targetId   the id of the target, or {@code null} when not applicable
     * @param summary    a short human-readable description of the action
     */
    public void record(String actor, AuditAction action, String targetType,
                       Long targetId, String summary) {
        try {
            recorder.save(actor, action, targetType, targetId, summary);
        } catch (RuntimeException ex) {
            log.warn("Failed to record audit event action={} actor={} targetType={} targetId={}",
                    action, actor, targetType, targetId, ex);
        }
    }

    /**
     * Returns a page of audit events, optionally filtered by actor and/or action.
     * A {@code null} filter argument disables that filter.
     *
     * @param actor    the actor to filter by, or {@code null} for any actor
     * @param action   the action to filter by, or {@code null} for any action
     * @param pageable pagination and sorting information
     * @return a page of {@link AuditEventResponse}
     */
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> list(String actor, AuditAction action, Pageable pageable) {
        return repository.search(actor, action, pageable)
                .map(AuditEventResponse::from);
    }
}
