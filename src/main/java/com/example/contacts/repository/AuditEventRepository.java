package com.example.contacts.repository;

import com.example.contacts.model.AuditAction;
import com.example.contacts.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link AuditEvent} entities.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * Returns a page of audit events, optionally filtered by actor and/or action.
     * A {@code null} filter argument disables that filter, so passing {@code null}
     * for both returns every event. Ordering is governed by the supplied
     * {@link Pageable} (the controller defaults to newest-first).
     *
     * @param actor    the actor to filter by, or {@code null} for any actor
     * @param action   the action to filter by, or {@code null} for any action
     * @param pageable pagination and sorting information
     * @return a page of matching audit events
     */
    @Query("""
        SELECT a FROM AuditEvent a
        WHERE (:actor IS NULL OR a.actor = :actor)
          AND (:action IS NULL OR a.action = :action)
        """)
    Page<AuditEvent> search(@Param("actor") String actor,
                            @Param("action") AuditAction action,
                            Pageable pageable);
}
