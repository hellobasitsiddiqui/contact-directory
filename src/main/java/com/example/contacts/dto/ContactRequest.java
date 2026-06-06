package com.example.contacts.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Set;

/**
 * Request body for creating (POST) or fully replacing (PUT) a contact.
 *
 * <p>All required fields must be supplied; validation is enforced via Jakarta
 * Bean Validation constraints when the record is bound by the controller.
 *
 * @param firstName the contact's first name; must not be blank
 * @param lastName  the contact's last name; must not be blank
 * @param email     the contact's email address; must not be blank and must be well-formed
 * @param phone     the contact's phone number; optional, but if present must match the allowed format
 * @param company   the contact's company; optional
 * @param tags      the set of labels to assign; optional ({@code null} is treated as no tags)
 * @param favorite  whether the contact is a favourite; defaults to {@code false}
 * @param notes     freetext notes about the contact; optional
 */
public record ContactRequest(

        @NotBlank
        String firstName,

        @NotBlank
        String lastName,

        @NotBlank
        @Email
        String email,

        @Pattern(regexp = "^[+0-9 ()\\-]{7,20}$", message = "must be a valid phone number")
        String phone,

        String company,

        Set<String> tags,

        boolean favorite,

        String notes) {
}
