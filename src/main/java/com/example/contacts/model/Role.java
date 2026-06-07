package com.example.contacts.model;

/**
 * Application roles. Stored on {@link User} and mapped to Spring Security
 * authorities with a {@code ROLE_} prefix (e.g. {@code ROLE_ADMIN}).
 */
public enum Role {
    USER,
    ADMIN
}
