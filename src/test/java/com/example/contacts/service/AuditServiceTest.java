package com.example.contacts.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.contacts.model.AuditAction;
import com.example.contacts.repository.AuditEventRepository;

import org.junit.jupiter.api.Test;

/**
 * Unit tests (no Spring context) for {@link AuditService}, focused on its
 * resilience contract: a failure to persist an audit event must be swallowed so
 * it can never break the operation being audited.
 */
class AuditServiceTest {

    /**
     * The persistence runs via {@link AuditRecorder}. When that throws (the
     * realistic failure mode being a flush/commit error in its isolated
     * transaction), {@link AuditService#record} must log and swallow it rather
     * than letting it propagate to the caller.
     */
    @Test
    void record_swallowsPersistenceFailure() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        AuditRecorder recorder = mock(AuditRecorder.class);
        doThrow(new RuntimeException("db down"))
                .when(recorder).save(any(), any(), any(), any(), any());
        AuditService service = new AuditService(repository, recorder);

        assertThatCode(() -> service.record(
                "bob", AuditAction.CONTACT_CREATE, "CONTACT", 1L, "x"))
                .doesNotThrowAnyException();

        verify(recorder).save(
                eq("bob"), eq(AuditAction.CONTACT_CREATE), eq("CONTACT"), eq(1L), eq("x"));
    }
}
