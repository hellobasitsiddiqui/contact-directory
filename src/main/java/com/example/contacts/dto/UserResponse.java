package com.example.contacts.dto;

import com.example.contacts.model.User;

import java.time.Instant;

/**
 * Public view of a {@link User} account (never exposes the password hash).
 */
public record UserResponse(
        Long id,
        String username,
        String role,
        boolean enabled,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getRole().name(),
                user.isEnabled(),
                user.getCreatedAt());
    }
}
