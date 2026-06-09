package com.example.contacts.model;

/**
 * The set of auditable actions recorded in the append-only audit log.
 *
 * <p>Each value names a single mutating operation performed somewhere in the
 * application. Stored on {@link AuditEvent} as a string (via
 * {@code @Enumerated(EnumType.STRING)}) so the persisted history stays readable
 * and stable against enum reordering.
 */
public enum AuditAction {
    CONTACT_CREATE,
    CONTACT_UPDATE,
    CONTACT_DELETE,
    CONTACT_RESTORE,
    CONTACT_PURGE,
    CONTACT_BULK_DELETE,
    CONTACT_BULK_FAVORITE,
    CONTACT_BULK_TAGS,
    CONTACT_IMPORT,
    CONTACT_PHOTO_UPDATE,
    CONTACT_PHOTO_DELETE,
    USER_ROLE_CHANGE,
    USER_ENABLED_CHANGE,
    USER_PASSWORD_RESET,
    USER_DELETE,
    AUTH_REGISTER,
    AUTH_LOGIN,
    AUTH_PASSWORD_CHANGE,
    AUTH_LOGOUT,
    AUTH_TOKEN_REUSE
}
