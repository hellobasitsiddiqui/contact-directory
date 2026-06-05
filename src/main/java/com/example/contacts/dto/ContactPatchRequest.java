package com.example.contacts.dto;

import jakarta.validation.constraints.Email;

import java.util.Set;

/**
 * Request body for partially updating (PATCH) a contact.
 *
 * <p>Every field is optional: only non-{@code null} values are applied to the
 * target contact. When {@code email} is supplied it must be a well-formed
 * address, but no fields are mandatory.
 *
 * @param firstName new first name, or {@code null} to leave unchanged
 * @param lastName  new last name, or {@code null} to leave unchanged
 * @param email     new email address, or {@code null} to leave unchanged; must be well-formed if present
 * @param phone     new phone number, or {@code null} to leave unchanged
 * @param company   new company, or {@code null} to leave unchanged
 * @param tags      new set of tags, or {@code null} to leave unchanged
 * @param favorite  new favourite flag, or {@code null} to leave unchanged
 */
public record ContactPatchRequest(

        String firstName,

        String lastName,

        @Email
        String email,

        String phone,

        String company,

        Set<String> tags,

        Boolean favorite) {
}
