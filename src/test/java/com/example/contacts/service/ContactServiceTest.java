package com.example.contacts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.contacts.dto.ContactPatchRequest;
import com.example.contacts.dto.ContactRequest;
import com.example.contacts.dto.ContactResponse;
import com.example.contacts.exception.DuplicateEmailException;
import com.example.contacts.exception.ResourceNotFoundException;
import com.example.contacts.exception.StaleResourceException;
import com.example.contacts.model.Contact;
import com.example.contacts.repository.ContactRepository;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit tests for {@link ContactService} using Mockito only — no Spring
 * context is started. The {@link ContactRepository} collaborator is mocked and
 * injected into the service under test.
 *
 * <p>Every service method is owner-aware, taking the current user's id
 * ({@link #OWNER_ID}) and whether they are an administrator. These tests exercise
 * the non-admin (owner-scoped) path, stubbing the owner-scoped repository
 * methods so a contact owned by someone else is treated as missing.
 */
@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    /** Id of the (non-admin) user owning the contacts under test. */
    private static final long OWNER_ID = 1000L;

    @Mock
    private ContactRepository repository;

    @InjectMocks
    private ContactService service;

    private ContactRequest validRequest() {
        return new ContactRequest("Ada", "Lovelace", "ada@example.com",
                "+44 20 7946 0958", "Analytical Engines", null, false, null, null);
    }

    /** Builds a contact owned by {@link #OWNER_ID} with the given id. */
    private Contact ownedContact(long id) {
        Contact contact = new Contact();
        contact.setId(id);
        contact.setOwnerId(OWNER_ID);
        return contact;
    }

    @Test
    void create_happyPath_savesAndReturnsResponseWithGeneratedIdAndOwner() {
        ContactRequest req = validRequest();
        when(repository.existsByEmailIgnoreCaseAndOwnerId(req.email(), OWNER_ID)).thenReturn(false);
        // Stub save to assign an id and timestamps, mimicking JPA persistence.
        when(repository.save(any(Contact.class))).thenAnswer(invocation -> {
            Contact toSave = invocation.getArgument(0);
            toSave.setId(42L);
            toSave.setCreatedAt(Instant.now());
            toSave.setUpdatedAt(Instant.now());
            return toSave;
        });

        ContactResponse response = service.create(req, OWNER_ID);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.firstName()).isEqualTo("Ada");
        assertThat(response.lastName()).isEqualTo("Lovelace");
        assertThat(response.email()).isEqualTo("ada@example.com");
        assertThat(response.phone()).isEqualTo("+44 20 7946 0958");
        assertThat(response.company()).isEqualTo("Analytical Engines");

        // The new contact must be stamped with the creating owner's id.
        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOwnerId()).isEqualTo(OWNER_ID);
    }

    @Test
    void create_duplicateEmailForSameOwner_throwsDuplicateEmailExceptionAndDoesNotSave() {
        ContactRequest req = validRequest();
        when(repository.existsByEmailIgnoreCaseAndOwnerId(req.email(), OWNER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.create(req, OWNER_ID))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessageContaining("ada@example.com");

        verify(repository, never()).save(any(Contact.class));
    }

    @Test
    void get_missingId_throwsResourceNotFoundException() {
        when(repository.findByIdAndOwnerId(99L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L, OWNER_ID, false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void get_contactOwnedByAnotherUser_isTreatedAsMissing() {
        // Owner-scoped lookup returns empty even though the row exists for someone
        // else, so the existence of another owner's contact is never revealed.
        when(repository.findByIdAndOwnerId(7L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(7L, OWNER_ID, false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("7");
    }

    @Test
    void get_asAdmin_usesUnscopedLookup() {
        Contact contact = ownedContact(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(contact));

        ContactResponse response = service.get(7L, OWNER_ID, true);

        assertThat(response.id()).isEqualTo(7L);
        verify(repository, never()).findByIdAndOwnerId(any(), any());
    }

    @Test
    void delete_missingId_throwsResourceNotFoundExceptionAndDoesNotDelete() {
        when(repository.findByIdAndOwnerId(99L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L, OWNER_ID, false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verify(repository, never()).delete(any());
    }

    // ---- Soft delete / restore / purge ------------------------------------

    @Test
    void delete_existingId_softDeletesByStampingDeletedAtAndSaving() {
        Contact contact = ownedContact(7L);
        when(repository.findByIdAndOwnerId(7L, OWNER_ID)).thenReturn(Optional.of(contact));

        service.delete(7L, OWNER_ID, false);

        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
        // Soft delete must never hard-delete the row.
        verify(repository, never()).delete(any());
    }

    @Test
    void restore_existingId_clearsDeletedAtAndReturnsResponse() {
        Contact contact = ownedContact(7L);
        contact.setDeletedAt(Instant.now());
        when(repository.findByIdAndOwnerId(7L, OWNER_ID)).thenReturn(Optional.of(contact));
        when(repository.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

        ContactResponse response = service.restore(7L, OWNER_ID, false);

        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDeletedAt()).isNull();
        assertThat(response.deletedAt()).isNull();
    }

    @Test
    void restore_missingId_throwsResourceNotFoundException() {
        when(repository.findByIdAndOwnerId(99L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(99L, OWNER_ID, false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verify(repository, never()).save(any(Contact.class));
    }

    @Test
    void purge_existingId_hardDeletes() {
        Contact contact = ownedContact(7L);
        when(repository.findByIdAndOwnerId(7L, OWNER_ID)).thenReturn(Optional.of(contact));

        service.purge(7L, OWNER_ID, false);

        verify(repository).delete(contact);
    }

    @Test
    void purge_missingId_throwsResourceNotFoundExceptionAndDoesNotDelete() {
        when(repository.findByIdAndOwnerId(99L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purge(99L, OWNER_ID, false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verify(repository, never()).delete(any());
    }

    // ---- Optimistic concurrency (version check) ---------------------------

    @Test
    void update_staleVersion_throwsStaleResourceExceptionAndDoesNotSave() {
        Contact contact = ownedContact(7L);
        contact.setVersion(3L);
        when(repository.findByIdAndOwnerId(7L, OWNER_ID)).thenReturn(Optional.of(contact));

        // Client supplies a different version than the persisted one.
        ContactRequest req = new ContactRequest("Ada", "Lovelace", "ada@example.com",
                null, null, null, false, null, 99L);

        assertThatThrownBy(() -> service.update(7L, req, OWNER_ID, false))
                .isInstanceOf(StaleResourceException.class);

        verify(repository, never()).save(any(Contact.class));
        verify(repository, never()).saveAndFlush(any(Contact.class));
    }

    @Test
    void update_nullVersion_skipsCheckAndSaves() {
        Contact contact = ownedContact(7L);
        contact.setVersion(3L);
        when(repository.findByIdAndOwnerId(7L, OWNER_ID)).thenReturn(Optional.of(contact));
        // Email uniqueness is enforced against the target contact's owner.
        when(repository.existsByEmailIgnoreCaseAndOwnerIdAndIdNot(any(), eq(OWNER_ID), any()))
                .thenReturn(false);
        // update() flushes so the @Version increment is reflected in the response.
        when(repository.saveAndFlush(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

        // Null version -> the optimistic check is skipped entirely.
        ContactRequest req = new ContactRequest("Ada", "Lovelace", "ada@example.com",
                null, null, null, false, null, null);

        service.update(7L, req, OWNER_ID, false);

        verify(repository).saveAndFlush(any(Contact.class));
    }

    @Test
    void update_duplicateEmailForOwner_throwsDuplicateEmailExceptionAndDoesNotSave() {
        Contact contact = ownedContact(7L);
        when(repository.findByIdAndOwnerId(7L, OWNER_ID)).thenReturn(Optional.of(contact));
        // The owner already has a different contact using this email.
        when(repository.existsByEmailIgnoreCaseAndOwnerIdAndIdNot("ada@example.com", OWNER_ID, 7L))
                .thenReturn(true);

        ContactRequest req = new ContactRequest("Ada", "Lovelace", "ada@example.com",
                null, null, null, false, null, null);

        assertThatThrownBy(() -> service.update(7L, req, OWNER_ID, false))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessageContaining("ada@example.com");

        verify(repository, never()).saveAndFlush(any(Contact.class));
    }

    @Test
    void patch_staleVersion_throwsStaleResourceExceptionAndDoesNotSave() {
        Contact contact = ownedContact(7L);
        contact.setVersion(2L);
        when(repository.findByIdAndOwnerId(7L, OWNER_ID)).thenReturn(Optional.of(contact));

        ContactPatchRequest req = new ContactPatchRequest(
                "NewName", null, null, null, null, null, null, null, 99L);

        assertThatThrownBy(() -> service.patch(7L, req, OWNER_ID, false))
                .isInstanceOf(StaleResourceException.class);

        verify(repository, never()).save(any(Contact.class));
        verify(repository, never()).saveAndFlush(any(Contact.class));
    }

    // ---- Bulk operations --------------------------------------------------

    @Test
    void bulkDelete_softDeletesExistingActiveIdsAndSkipsMissing() {
        Contact a = ownedContact(1L);
        Contact b = ownedContact(2L);
        when(repository.findByIdAndOwnerId(1L, OWNER_ID)).thenReturn(Optional.of(a));
        when(repository.findByIdAndOwnerId(2L, OWNER_ID)).thenReturn(Optional.of(b));
        when(repository.findByIdAndOwnerId(99L, OWNER_ID)).thenReturn(Optional.empty());
        when(repository.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

        int affected = service.bulkDelete(java.util.List.of(1L, 2L, 99L), OWNER_ID, false);

        assertThat(affected).isEqualTo(2);
        assertThat(a.getDeletedAt()).isNotNull();
        assertThat(b.getDeletedAt()).isNotNull();
    }

    @Test
    void bulkDelete_skipsAlreadySoftDeleted() {
        Contact active = ownedContact(1L);
        Contact trashed = ownedContact(2L);
        trashed.setDeletedAt(Instant.now());
        when(repository.findByIdAndOwnerId(1L, OWNER_ID)).thenReturn(Optional.of(active));
        when(repository.findByIdAndOwnerId(2L, OWNER_ID)).thenReturn(Optional.of(trashed));
        when(repository.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

        int affected = service.bulkDelete(java.util.List.of(1L, 2L), OWNER_ID, false);

        // Only the still-active contact is counted.
        assertThat(affected).isEqualTo(1);
    }

    @Test
    void bulkDelete_contactOwnedByAnotherUser_isSkipped() {
        Contact mine = ownedContact(1L);
        when(repository.findByIdAndOwnerId(1L, OWNER_ID)).thenReturn(Optional.of(mine));
        // Id 2 belongs to another owner -> owner-scoped lookup returns empty.
        when(repository.findByIdAndOwnerId(2L, OWNER_ID)).thenReturn(Optional.empty());
        when(repository.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

        int affected = service.bulkDelete(java.util.List.of(1L, 2L), OWNER_ID, false);

        // Another owner's contact is treated exactly like a missing one.
        assertThat(affected).isEqualTo(1);
        assertThat(mine.getDeletedAt()).isNotNull();
    }

    @Test
    void bulkSetFavorite_setsFlagOnActiveIdsAndCountsAffected() {
        Contact a = ownedContact(1L);
        Contact b = ownedContact(2L);
        when(repository.findByIdAndOwnerId(1L, OWNER_ID)).thenReturn(Optional.of(a));
        when(repository.findByIdAndOwnerId(2L, OWNER_ID)).thenReturn(Optional.of(b));
        when(repository.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

        int affected = service.bulkSetFavorite(java.util.List.of(1L, 2L), true, OWNER_ID, false);

        assertThat(affected).isEqualTo(2);
        assertThat(a.isFavorite()).isTrue();
        assertThat(b.isFavorite()).isTrue();
    }

    @Test
    void bulkAddRemoveTags_addsAndRemovesTagsOnActiveIds() {
        Contact a = ownedContact(1L);
        a.setTags(new java.util.LinkedHashSet<>(java.util.Set.of("Old")));
        when(repository.findByIdAndOwnerId(1L, OWNER_ID)).thenReturn(Optional.of(a));
        when(repository.save(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));

        int affected = service.bulkAddRemoveTags(
                java.util.List.of(1L), java.util.Set.of("New"), java.util.Set.of("Old"),
                OWNER_ID, false);

        assertThat(affected).isEqualTo(1);
        assertThat(a.getTags()).contains("New");
        assertThat(a.getTags()).doesNotContain("Old");
    }

    @Test
    void bulkDelete_emptyList_returnsZeroAndDoesNotSave() {
        int affected = service.bulkDelete(java.util.List.of(), OWNER_ID, false);

        assertThat(affected).isZero();
        verify(repository, never()).save(any(Contact.class));
    }

    // ---- Photo operations -------------------------------------------------

    @Test
    void getPhoto_missingContact_throwsResourceNotFoundException() {
        when(repository.findByIdAndOwnerId(99L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPhoto(99L, OWNER_ID, false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void getPhoto_contactHasNoPhoto_throwsResourceNotFoundException() {
        Contact contact = ownedContact(7L);
        // no photo / no photoContentType set -> both null
        when(repository.findByIdAndOwnerId(7L, OWNER_ID)).thenReturn(Optional.of(contact));

        assertThatThrownBy(() -> service.getPhoto(7L, OWNER_ID, false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No photo");
    }

    @Test
    void getPhoto_happyPath_returnsBytesAndContentType() {
        byte[] bytes = {1, 2, 3};
        Contact contact = ownedContact(7L);
        contact.setPhoto(bytes);
        contact.setPhotoContentType("image/png");
        when(repository.findByIdAndOwnerId(7L, OWNER_ID)).thenReturn(Optional.of(contact));

        PhotoData data = service.getPhoto(7L, OWNER_ID, false);

        assertThat(data.data()).isEqualTo(bytes);
        assertThat(data.contentType()).isEqualTo("image/png");
    }

    @Test
    void savePhoto_missingContact_throwsResourceNotFoundException() {
        when(repository.findByIdAndOwnerId(99L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.savePhoto(99L, new byte[]{1}, "image/png", OWNER_ID, false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verify(repository, never()).save(any(Contact.class));
    }

    @Test
    void savePhoto_happyPath_setsFieldsAndSaves() {
        byte[] bytes = {4, 5, 6};
        Contact contact = ownedContact(7L);
        when(repository.findByIdAndOwnerId(7L, OWNER_ID)).thenReturn(Optional.of(contact));

        service.savePhoto(7L, bytes, "image/jpeg", OWNER_ID, false);

        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(repository).save(captor.capture());
        Contact saved = captor.getValue();
        assertThat(saved.getPhoto()).isEqualTo(bytes);
        assertThat(saved.getPhotoContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void deletePhoto_missingContact_throwsResourceNotFoundException() {
        when(repository.findByIdAndOwnerId(99L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deletePhoto(99L, OWNER_ID, false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verify(repository, never()).save(any(Contact.class));
    }

    @Test
    void deletePhoto_happyPath_clearsFieldsAndSaves() {
        Contact contact = ownedContact(7L);
        contact.setPhoto(new byte[]{9});
        contact.setPhotoContentType("image/png");
        when(repository.findByIdAndOwnerId(7L, OWNER_ID)).thenReturn(Optional.of(contact));

        service.deletePhoto(7L, OWNER_ID, false);

        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(repository).save(captor.capture());
        Contact saved = captor.getValue();
        assertThat(saved.getPhoto()).isNull();
        assertThat(saved.getPhotoContentType()).isNull();
    }
}
