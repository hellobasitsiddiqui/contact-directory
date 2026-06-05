package com.example.contacts.service;

import com.example.contacts.dto.ContactPatchRequest;
import com.example.contacts.dto.ContactRequest;
import com.example.contacts.dto.ContactResponse;
import com.example.contacts.exception.DuplicateEmailException;
import com.example.contacts.exception.ResourceNotFoundException;
import com.example.contacts.model.Contact;
import com.example.contacts.repository.ContactRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service encapsulating the business logic for managing
 * {@link Contact} entities in the Contact Directory.
 *
 * <p>All read operations run in a read-only transaction; all mutating
 * operations run in a read-write transaction. Entities are mapped to
 * {@link ContactResponse} before being returned so the persistence model
 * never leaks beyond this layer.
 */
@Service
public class ContactService {

    private final ContactRepository repository;

    /**
     * Creates a new service backed by the given repository.
     *
     * @param repository the contact repository (constructor injected)
     */
    public ContactService(ContactRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns a page of contacts, optionally filtered by a free-text search term.
     *
     * <p>When {@code search} is {@code null} or blank, all contacts are returned;
     * otherwise the trimmed term is matched against first name, last name, email
     * and company.
     *
     * @param search   optional free-text search term
     * @param pageable pagination and sorting information
     * @return a page of {@link ContactResponse} projections
     */
    @Transactional(readOnly = true)
    public Page<ContactResponse> list(String search, Pageable pageable) {
        Page<Contact> contacts = (search == null || search.isBlank())
                ? repository.findAll(pageable)
                : repository.search(search.trim(), pageable);
        return contacts.map(ContactResponse::from);
    }

    /**
     * Retrieves a single contact by its identifier.
     *
     * @param id the contact identifier
     * @return the matching {@link ContactResponse}
     * @throws ResourceNotFoundException if no contact exists with the given id
     */
    @Transactional(readOnly = true)
    public ContactResponse get(Long id) {
        Contact contact = findByIdOrThrow(id);
        return ContactResponse.from(contact);
    }

    /**
     * Creates a new contact.
     *
     * @param req the validated contact request body
     * @return the persisted contact as a {@link ContactResponse}
     * @throws DuplicateEmailException if a contact with the same email already exists
     */
    @Transactional
    public ContactResponse create(ContactRequest req) {
        if (repository.existsByEmailIgnoreCase(req.email())) {
            throw new DuplicateEmailException(
                    "A contact with email '" + req.email() + "' already exists");
        }
        Contact contact = new Contact();
        applyRequest(contact, req);
        return ContactResponse.from(repository.save(contact));
    }

    /**
     * Fully replaces an existing contact with the supplied values.
     *
     * @param id  the identifier of the contact to update
     * @param req the validated contact request body
     * @return the updated contact as a {@link ContactResponse}
     * @throws ResourceNotFoundException if no contact exists with the given id
     * @throws DuplicateEmailException   if another contact already uses the email
     */
    @Transactional
    public ContactResponse update(Long id, ContactRequest req) {
        Contact contact = findByIdOrThrow(id);
        if (repository.existsByEmailIgnoreCaseAndIdNot(req.email(), id)) {
            throw new DuplicateEmailException(
                    "A contact with email '" + req.email() + "' already exists");
        }
        applyRequest(contact, req);
        return ContactResponse.from(repository.save(contact));
    }

    /**
     * Partially updates an existing contact, applying only the non-null fields
     * present in the patch request.
     *
     * @param id  the identifier of the contact to patch
     * @param req the patch request body (all fields optional)
     * @return the updated contact as a {@link ContactResponse}
     * @throws ResourceNotFoundException if no contact exists with the given id
     * @throws DuplicateEmailException   if the supplied email is already in use by another contact
     */
    @Transactional
    public ContactResponse patch(Long id, ContactPatchRequest req) {
        Contact contact = findByIdOrThrow(id);
        if (req.email() != null
                && repository.existsByEmailIgnoreCaseAndIdNot(req.email(), id)) {
            throw new DuplicateEmailException(
                    "A contact with email '" + req.email() + "' already exists");
        }
        if (req.firstName() != null) {
            contact.setFirstName(req.firstName());
        }
        if (req.lastName() != null) {
            contact.setLastName(req.lastName());
        }
        if (req.email() != null) {
            contact.setEmail(req.email());
        }
        if (req.phone() != null) {
            contact.setPhone(req.phone());
        }
        if (req.company() != null) {
            contact.setCompany(req.company());
        }
        return ContactResponse.from(repository.save(contact));
    }

    /**
     * Deletes a contact by its identifier.
     *
     * @param id the identifier of the contact to delete
     * @throws ResourceNotFoundException if no contact exists with the given id
     */
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Contact not found with id: " + id);
        }
        repository.deleteById(id);
    }

    /**
     * Loads the photo for a contact, reading the lazily-fetched blob inside the
     * transaction so it is fully materialised before being returned.
     *
     * @param id the contact identifier
     * @return the photo bytes and content type wrapped in a {@link PhotoData}
     * @throws ResourceNotFoundException if no contact exists with the given id,
     *                                   or if the contact has no photo
     */
    @Transactional(readOnly = true)
    public PhotoData getPhoto(Long id) {
        Contact contact = findByIdOrThrow(id);
        if (contact.getPhotoContentType() == null || contact.getPhoto() == null) {
            throw new ResourceNotFoundException("No photo for contact with id: " + id);
        }
        return new PhotoData(contact.getPhoto(), contact.getPhotoContentType());
    }

    /**
     * Stores (or replaces) the photo for a contact.
     *
     * <p>File type and size validation is performed in the controller, not here.
     *
     * @param id          the contact identifier
     * @param data        the raw image bytes
     * @param contentType the image MIME type
     * @throws ResourceNotFoundException if no contact exists with the given id
     */
    @Transactional
    public void savePhoto(Long id, byte[] data, String contentType) {
        Contact contact = findByIdOrThrow(id);
        contact.setPhoto(data);
        contact.setPhotoContentType(contentType);
        repository.save(contact);
    }

    /**
     * Removes the photo from a contact, clearing both the bytes and the
     * content-type flag.
     *
     * @param id the contact identifier
     * @throws ResourceNotFoundException if no contact exists with the given id
     */
    @Transactional
    public void deletePhoto(Long id) {
        Contact contact = findByIdOrThrow(id);
        contact.setPhoto(null);
        contact.setPhotoContentType(null);
        repository.save(contact);
    }

    /**
     * Loads a contact by id or throws a {@link ResourceNotFoundException}.
     *
     * @param id the contact identifier
     * @return the matching entity
     */
    private Contact findByIdOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Contact not found with id: " + id));
    }

    /**
     * Copies every field from a full {@link ContactRequest} onto the given entity.
     *
     * @param contact the target entity
     * @param req     the source request
     */
    private void applyRequest(Contact contact, ContactRequest req) {
        contact.setFirstName(req.firstName());
        contact.setLastName(req.lastName());
        contact.setEmail(req.email());
        contact.setPhone(req.phone());
        contact.setCompany(req.company());
    }
}
