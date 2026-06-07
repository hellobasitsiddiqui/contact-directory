package com.example.contacts.service;

import com.example.contacts.model.AuditAction;
import com.example.contacts.model.AuditEvent;
import com.example.contacts.repository.AuditEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists a single audit event in its own, independent transaction.
 *
 * <p>This collaborator exists purely so that the audit write runs under
 * {@link Propagation#REQUIRES_NEW}: if the write fails (e.g. a constraint
 * violation surfaced at flush/commit time), only this isolated transaction is
 * marked rollback-only and rolled back. The failure can then be caught and
 * swallowed by the caller without poisoning any surrounding transaction's
 * commit. It lives in a separate bean (rather than as a self-invoked method on
 * {@link AuditService}) because self-invocation bypasses the transactional
 * proxy and would not start a new transaction.
 */
@Component
public class AuditRecorder {

    private final AuditEventRepository repository;

    /**
     * Creates the recorder backed by the given repository.
     *
     * @param repository the audit-event repository (constructor injected)
     */
    public AuditRecorder(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Persists a single audit event in a brand-new transaction, stamping it with
     * the current instant. A flush/commit failure rolls back only this
     * transaction and propagates so the caller can log and swallow it.
     *
     * @param actor      the username of the principal who performed the action
     * @param action     the action that was performed
     * @param targetType the kind of target acted upon (e.g. {@code "CONTACT"})
     * @param targetId   the id of the target, or {@code null} when not applicable
     * @param summary    a short human-readable description of the action
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(String actor, AuditAction action, String targetType,
                     Long targetId, String summary) {
        repository.save(new AuditEvent(
                Instant.now(), actor, action, targetType, targetId, summary));
    }
}
