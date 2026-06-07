package com.example.contacts.dto;

import com.example.contacts.model.Role;
import jakarta.validation.constraints.NotNull;

/**
 * Body for changing a user's role.
 */
public record UpdateRoleRequest(
        @NotNull(message = "Role is required") Role role) {
}
