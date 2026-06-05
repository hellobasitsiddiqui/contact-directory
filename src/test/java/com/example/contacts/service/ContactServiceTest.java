package com.example.contacts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.contacts.dto.ContactRequest;
import com.example.contacts.dto.ContactResponse;
import com.example.contacts.exception.DuplicateEmailException;
import com.example.contacts.exception.ResourceNotFoundException;
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
 */
@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    @Mock
    private ContactRepository repository;

    @InjectMocks
    private ContactService service;

    private ContactRequest validRequest() {
        return new ContactRequest("Ada", "Lovelace", "ada@example.com",
                "+44 20 7946 0958", "Analytical Engines", null, false);
    }

    @Test
    void create_happyPath_savesAndReturnsResponseWithGeneratedId() {
        ContactRequest req = validRequest();
        when(repository.existsByEmailIgnoreCase(req.email())).thenReturn(false);
        // Stub save to assign an id and timestamps, mimicking JPA persistence.
        when(repository.save(any(Contact.class))).thenAnswer(invocation -> {
            Contact toSave = invocation.getArgument(0);
            toSave.setId(42L);
            toSave.setCreatedAt(Instant.now());
            toSave.setUpdatedAt(Instant.now());
            return toSave;
        });

        ContactResponse response = service.create(req);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.firstName()).isEqualTo("Ada");
        assertThat(response.lastName()).isEqualTo("Lovelace");
        assertThat(response.email()).isEqualTo("ada@example.com");
        assertThat(response.phone()).isEqualTo("+44 20 7946 0958");
        assertThat(response.company()).isEqualTo("Analytical Engines");
        verify(repository).save(any(Contact.class));
    }

    @Test
    void create_duplicateEmail_throwsDuplicateEmailExceptionAndDoesNotSave() {
        ContactRequest req = validRequest();
        when(repository.existsByEmailIgnoreCase(req.email())).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessageContaining("ada@example.com");

        verify(repository, never()).save(any(Contact.class));
    }

    @Test
    void get_missingId_throwsResourceNotFoundException() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void delete_missingId_throwsResourceNotFoundExceptionAndDoesNotDelete() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verify(repository, never()).deleteById(any());
    }

    // ---- Photo operations -------------------------------------------------

    @Test
    void getPhoto_missingContact_throwsResourceNotFoundException() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPhoto(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void getPhoto_contactHasNoPhoto_throwsResourceNotFoundException() {
        Contact contact = new Contact();
        contact.setId(7L);
        // no photo / no photoContentType set -> both null
        when(repository.findById(7L)).thenReturn(Optional.of(contact));

        assertThatThrownBy(() -> service.getPhoto(7L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No photo");
    }

    @Test
    void getPhoto_happyPath_returnsBytesAndContentType() {
        byte[] bytes = {1, 2, 3};
        Contact contact = new Contact();
        contact.setId(7L);
        contact.setPhoto(bytes);
        contact.setPhotoContentType("image/png");
        when(repository.findById(7L)).thenReturn(Optional.of(contact));

        PhotoData data = service.getPhoto(7L);

        assertThat(data.data()).isEqualTo(bytes);
        assertThat(data.contentType()).isEqualTo("image/png");
    }

    @Test
    void savePhoto_missingContact_throwsResourceNotFoundException() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.savePhoto(99L, new byte[]{1}, "image/png"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verify(repository, never()).save(any(Contact.class));
    }

    @Test
    void savePhoto_happyPath_setsFieldsAndSaves() {
        byte[] bytes = {4, 5, 6};
        Contact contact = new Contact();
        contact.setId(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(contact));

        service.savePhoto(7L, bytes, "image/jpeg");

        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(repository).save(captor.capture());
        Contact saved = captor.getValue();
        assertThat(saved.getPhoto()).isEqualTo(bytes);
        assertThat(saved.getPhotoContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void deletePhoto_missingContact_throwsResourceNotFoundException() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deletePhoto(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verify(repository, never()).save(any(Contact.class));
    }

    @Test
    void deletePhoto_happyPath_clearsFieldsAndSaves() {
        Contact contact = new Contact();
        contact.setId(7L);
        contact.setPhoto(new byte[]{9});
        contact.setPhotoContentType("image/png");
        when(repository.findById(7L)).thenReturn(Optional.of(contact));

        service.deletePhoto(7L);

        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(repository).save(captor.capture());
        Contact saved = captor.getValue();
        assertThat(saved.getPhoto()).isNull();
        assertThat(saved.getPhotoContentType()).isNull();
    }
}
