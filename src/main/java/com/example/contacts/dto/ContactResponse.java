package com.example.contacts.dto;

import com.example.contacts.model.Contact;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Response representation of a contact returned to API clients.
 *
 * <p>This is an immutable view of a {@link Contact} entity, including its
 * generated identifier and audit timestamps.
 *
 * @param id        the unique identifier of the contact
 * @param firstName the contact's first name
 * @param lastName  the contact's last name
 * @param email     the contact's email address
 * @param phone     the contact's phone number, may be {@code null}
 * @param company   the contact's company, may be {@code null}
 * @param createdAt the instant the contact was created
 * @param updatedAt the instant the contact was last updated
 * @param photoUrl  the URL to fetch the contact's photo, or {@code null} when
 *                  the contact has no photo
 * @param tags      the set of labels assigned to the contact (never {@code null};
 *                  empty when the contact has no tags)
 * @param favorite  whether the contact is marked as a favourite
 * @param notes     freetext notes about the contact, may be {@code null}
 */
public record ContactResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String company,
        Instant createdAt,
        Instant updatedAt,
        String photoUrl,
        Set<String> tags,
        boolean favorite,
        String notes) {

    /**
     * Maps a {@link Contact} entity to a {@link ContactResponse} DTO,
     * copying every field.
     *
     * <p>The {@code photoUrl} is derived solely from
     * {@link Contact#getPhotoContentType()} (a normal eager column used as the
     * "has photo" flag); the raw photo bytes are never read here, so mapping a
     * contact never triggers loading of the lazy photo blob.
     *
     * @param c the contact entity to map; must not be {@code null}
     * @return a fully populated {@link ContactResponse}
     */
    public static ContactResponse from(Contact c) {
        String photoUrl = (c.getPhotoContentType() != null)
                ? "/api/v1/contacts/" + c.getId() + "/photo"
                : null;
        // Copy into a new set so the DTO never holds a reference to the managed
        // (lazy) persistent collection.
        Set<String> tags = new LinkedHashSet<>(c.getTags());
        return new ContactResponse(
                c.getId(),
                c.getFirstName(),
                c.getLastName(),
                c.getEmail(),
                c.getPhone(),
                c.getCompany(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                photoUrl,
                tags,
                c.isFavorite(),
                c.getNotes());
    }
}
