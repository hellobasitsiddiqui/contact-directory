package com.example.contacts.dto;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/contacts/bulk/favorite}, setting the
 * favourite flag on multiple contacts at once.
 *
 * @param ids      the ids of the contacts to update; missing or already
 *                 soft-deleted ids are skipped by the service
 * @param favorite the favourite flag to apply to each contact
 */
public record BulkFavoriteRequest(List<Long> ids, boolean favorite) {
}
