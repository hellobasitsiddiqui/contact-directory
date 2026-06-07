package com.example.contacts.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body for enabling or disabling a user account.
 */
public record UpdateEnabledRequest(
        @NotNull(message = "enabled is required") Boolean enabled) {
}
