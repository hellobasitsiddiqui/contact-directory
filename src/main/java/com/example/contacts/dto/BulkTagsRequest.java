package com.example.contacts.dto;

import java.util.List;
import java.util.Set;

/**
 * Request body for {@code POST /api/v1/contacts/bulk/tags}, adding and/or
 * removing tags across multiple contacts at once.
 *
 * @param ids        the ids of the contacts to update; missing or already
 *                   soft-deleted ids are skipped by the service
 * @param addTags    the tags to add to each contact
 * @param removeTags the tags to remove from each contact
 */
public record BulkTagsRequest(List<Long> ids, Set<String> addTags, Set<String> removeTags) {
}
