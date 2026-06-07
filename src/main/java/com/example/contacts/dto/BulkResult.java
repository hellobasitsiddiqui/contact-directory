package com.example.contacts.dto;

/**
 * Response body for the bulk action endpoints.
 *
 * @param affected the number of contacts actually mutated; missing or already
 *                 soft-deleted ids are skipped and not counted
 */
public record BulkResult(int affected) {
}
