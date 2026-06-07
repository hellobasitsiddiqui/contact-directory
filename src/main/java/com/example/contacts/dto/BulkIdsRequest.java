package com.example.contacts.dto;

import java.util.List;

/**
 * Request body for a bulk action that operates purely on a set of contact ids,
 * such as {@code POST /api/v1/contacts/bulk/delete}.
 *
 * @param ids the ids of the contacts to act on; missing or already soft-deleted
 *            ids are skipped by the service
 */
public record BulkIdsRequest(List<Long> ids) {
}
