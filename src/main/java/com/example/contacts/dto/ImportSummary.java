package com.example.contacts.dto;

import java.util.List;

/**
 * Result of a CSV bulk import.
 *
 * @param imported the number of contacts successfully created
 * @param skipped  the number of rows skipped because their email already exists
 * @param errors   human-readable, per-row error messages for invalid rows
 */
public record ImportSummary(int imported, int skipped, List<String> errors) {
}
