package com.example.contacts.controller;

import com.example.contacts.dto.AuditEventResponse;
import com.example.contacts.model.AuditAction;
import com.example.contacts.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only API exposing a read view over the append-only audit log. The
 * {@code ADMIN} role is enforced at the class level; a {@code USER} calling this
 * endpoint receives {@code 403 Forbidden}.
 */
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Audit log", description = "Admin-only read access to the audit log")
public class AuditController {

    private final AuditService auditService;

    /**
     * Creates the controller with its required collaborator.
     *
     * @param auditService the service exposing the audit log
     */
    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Lists audit events newest-first, optionally filtered by actor and/or action.
     *
     * @param actor    optional username to filter events by
     * @param action   optional action (as its {@link AuditAction} enum name) to filter by
     * @param pageable pagination and sorting (defaults to size 20, sorted by
     *                 {@code timestamp} descending)
     * @return {@code 200 OK} with a page of {@link AuditEventResponse}
     */
    @GetMapping
    @Operation(summary = "List audit events",
            description = "Returns a paginated, newest-first list of audit events, "
                    + "optionally filtered by actor and/or action.")
    @ApiResponse(responseCode = "200", description = "Page of audit events")
    public ResponseEntity<Page<AuditEventResponse>> list(
            @RequestParam(required = false)
            @Parameter(description = "Restrict events to those performed by this actor")
            String actor,
            @RequestParam(required = false)
            @Parameter(description = "Restrict events to this action (AuditAction enum name)")
            AuditAction action,
            @ParameterObject
            @PageableDefault(size = 20, sort = "timestamp", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(auditService.list(actor, action, pageable));
    }
}
