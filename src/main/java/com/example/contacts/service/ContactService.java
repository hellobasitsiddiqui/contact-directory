package com.example.contacts.service;

import com.example.contacts.dto.ContactPatchRequest;
import com.example.contacts.dto.ContactRequest;
import com.example.contacts.dto.ContactResponse;
import com.example.contacts.dto.ImportSummary;
import com.example.contacts.exception.DuplicateEmailException;
import com.example.contacts.exception.ResourceNotFoundException;
import com.example.contacts.exception.StaleResourceException;
import com.example.contacts.model.Contact;
import com.example.contacts.repository.ContactRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
     * Returns a page of contacts, optionally filtered by a free-text search term
     * and/or a tag.
     *
     * <ul>
     *   <li>No search and no tag: all contacts.</li>
     *   <li>Search only: matched against first name, last name, email, company and phone.</li>
     *   <li>Tag (with or without search): only contacts carrying the tag, further
     *       narrowed by the search term when present.</li>
     * </ul>
     *
     * @param search   optional free-text search term
     * @param tag      optional tag to filter by
     * @param pageable pagination and sorting information
     * @return a page of {@link ContactResponse} projections
     */
    @Transactional(readOnly = true)
    public Page<ContactResponse> list(String search, String tag, Pageable pageable) {
        String q = (search == null) ? "" : search.trim();
        boolean hasTag = tag != null && !tag.isBlank();
        // Favourites are always pinned to the top, before the caller's sort.
        Pageable effective = withFavoritesFirst(pageable);

        Page<Contact> contacts;
        if (hasTag) {
            contacts = repository.searchAndFilterByTag(q, tag.trim(), effective);
        } else if (q.isEmpty()) {
            contacts = repository.findByDeletedAtIsNull(effective);
        } else {
            contacts = repository.search(q, effective);
        }
        return contacts.map(ContactResponse::from);
    }

    /**
     * Returns a copy of the given pageable whose sort is prefixed with
     * {@code favorite DESC}, so favourite contacts always appear first while the
     * caller's requested sort still applies within each group.
     *
     * @param pageable the incoming pageable
     * @return a pageable that pins favourites to the top
     */
    private Pageable withFavoritesFirst(Pageable pageable) {
        Sort sort = Sort.by(Sort.Order.desc("favorite")).and(pageable.getSort());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    /**
     * Returns the distinct set of tags currently in use across all contacts,
     * sorted case-insensitively. Used to populate the tag filter control.
     *
     * @return the sorted list of distinct tags
     */
    @Transactional(readOnly = true)
    public List<String> listTags() {
        return repository.findDistinctTags();
    }

    /* ----------------------------- Import / export ----------------------------- */

    /** CSV column order for export and the basis for header detection on import. */
    private static final List<String> CSV_COLUMNS =
            List.of("id", "firstName", "lastName", "email", "phone", "company", "tags", "favorite", "notes");

    /**
     * Returns every active (non-trashed) contact (unpaged), sorted by last then
     * first name, for a full export. Soft-deleted contacts are excluded.
     *
     * @return all active contacts as {@link ContactResponse} projections
     */
    @Transactional(readOnly = true)
    public List<ContactResponse> exportAll() {
        return repository.findByDeletedAtIsNull(
                        Sort.by(Sort.Order.asc("lastName"), Sort.Order.asc("firstName")))
                .stream()
                .map(ContactResponse::from)
                .toList();
    }

    /**
     * Renders every contact as a CSV document (header row + one row per contact;
     * tags joined with {@code ;}). Uses CRLF line endings.
     *
     * @return the CSV text
     */
    @Transactional(readOnly = true)
    public String exportCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append(CsvSupport.toRow(CSV_COLUMNS)).append("\r\n");
        for (ContactResponse c : exportAll()) {
            sb.append(CsvSupport.toRow(Arrays.asList(
                    String.valueOf(c.id()),
                    c.firstName(),
                    c.lastName(),
                    c.email(),
                    c.phone() == null ? "" : c.phone(),
                    c.company() == null ? "" : c.company(),
                    String.join(";", c.tags()),
                    String.valueOf(c.favorite()),
                    c.notes() == null ? "" : c.notes()
            ))).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * Bulk-imports contacts from CSV text. Supports an optional header row
     * (columns matched by name) and falls back to positional order
     * {@code firstName,lastName,email,phone,company,tags,favorite}. Rows whose
     * email already exists are skipped; invalid rows are reported in the summary.
     *
     * @param content the raw CSV text
     * @return a summary of imported / skipped counts and per-row errors
     */
    @Transactional
    public ImportSummary importCsv(String content) {
        List<List<String>> rows = CsvSupport.parse(content);
        List<String> errors = new ArrayList<>();
        if (rows.isEmpty()) {
            return new ImportSummary(0, 0, List.of());
        }

        Map<String, Integer> header = detectHeader(rows.get(0));
        int start = header != null ? 1 : 0;
        int imported = 0;
        int skipped = 0;

        for (int r = start; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            int lineNo = r + 1;
            if (row.stream().allMatch(s -> s == null || s.isBlank())) {
                continue; // ignore blank lines
            }

            String firstName = trimToNull(field(row, header, "firstName", 0));
            String lastName = trimToNull(field(row, header, "lastName", 1));
            String email = trimToNull(field(row, header, "email", 2));
            String phone = trimToNull(field(row, header, "phone", 3));
            String company = trimToNull(field(row, header, "company", 4));
            String tagsRaw = field(row, header, "tags", 5);
            String favRaw = field(row, header, "favorite", 6);
            String notes = trimToNull(field(row, header, "notes", 7));

            if (firstName == null || lastName == null || email == null) {
                errors.add("Row " + lineNo + ": firstName, lastName and email are required");
                continue;
            }
            if (!email.contains("@")) {
                errors.add("Row " + lineNo + ": invalid email '" + email + "'");
                continue;
            }
            if (repository.existsByEmailIgnoreCase(email)) {
                skipped++;
                continue;
            }

            Contact contact = new Contact();
            contact.setFirstName(firstName);
            contact.setLastName(lastName);
            contact.setEmail(email);
            contact.setPhone(phone);
            contact.setCompany(company);
            contact.setTags(parseTags(tagsRaw));
            contact.setFavorite(parseBoolean(favRaw));
            contact.setNotes(notes);
            repository.save(contact);
            imported++;
        }
        return new ImportSummary(imported, skipped, errors);
    }

    /**
     * Detects whether the first parsed row is a header (contains an "email"
     * column), returning a lower-cased column-name to index map, or {@code null}
     * when there is no header.
     */
    private Map<String, Integer> detectHeader(List<String> first) {
        boolean looksLikeHeader = first.stream()
                .map(s -> s == null ? "" : s.trim().toLowerCase(Locale.ROOT))
                .anyMatch("email"::equals);
        if (!looksLikeHeader) {
            return null;
        }
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < first.size(); i++) {
            String key = first.get(i) == null ? "" : first.get(i).trim().toLowerCase(Locale.ROOT);
            map.putIfAbsent(key, i);
        }
        return map;
    }

    /** Reads a field by header name when a header is present, else by position. */
    private String field(List<String> row, Map<String, Integer> header, String name, int positional) {
        int idx = header != null
                ? header.getOrDefault(name.toLowerCase(Locale.ROOT), -1)
                : positional;
        if (idx < 0 || idx >= row.size()) {
            return null;
        }
        return row.get(idx);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Split a tag cell on ';' or ',' into a deduped, ordered set. */
    private static Set<String> parseTags(String raw) {
        Set<String> tags = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return tags;
        }
        for (String part : raw.split("[;,]")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                tags.add(t);
            }
        }
        return tags;
    }

    private static boolean parseBoolean(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("y");
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
        // A soft-deleted contact lives in the trash, not the active directory, so
        // the normal GET endpoint treats it as not found (use /trash or restore).
        if (contact.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Contact not found with id: " + id);
        }
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
        checkVersion(req.version(), contact);
        if (repository.existsByEmailIgnoreCaseAndIdNot(req.email(), id)) {
            throw new DuplicateEmailException(
                    "A contact with email '" + req.email() + "' already exists");
        }
        applyRequest(contact, req);
        // Flush so the @Version increment is applied and reflected in the response.
        return ContactResponse.from(repository.saveAndFlush(contact));
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
        checkVersion(req.version(), contact);
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
        if (req.tags() != null) {
            contact.setTags(req.tags());
        }
        if (req.favorite() != null) {
            contact.setFavorite(req.favorite());
        }
        if (req.notes() != null) {
            contact.setNotes(req.notes());
        }
        // Flush so the @Version increment is applied and reflected in the response.
        return ContactResponse.from(repository.saveAndFlush(contact));
    }

    /**
     * Soft-deletes a contact by its identifier, stamping {@code deletedAt} with
     * the current instant and moving it to the trash. The row is retained so it
     * can later be restored or permanently purged. Re-stamping an
     * already-trashed contact is a no-op (its original {@code deletedAt} is kept).
     *
     * @param id the identifier of the contact to soft-delete
     * @throws ResourceNotFoundException if no contact exists with the given id
     */
    @Transactional
    public void delete(Long id) {
        Contact contact = findByIdOrThrow(id);
        if (contact.getDeletedAt() == null) {
            contact.setDeletedAt(Instant.now());
            repository.save(contact);
        }
    }

    /**
     * Returns a page of soft-deleted (trashed) contacts only.
     *
     * @param pageable pagination and sorting information
     * @return a page of trashed contacts as {@link ContactResponse} projections
     */
    @Transactional(readOnly = true)
    public Page<ContactResponse> listTrash(Pageable pageable) {
        return repository.findByDeletedAtIsNotNull(pageable).map(ContactResponse::from);
    }

    /**
     * Restores a soft-deleted contact by clearing its {@code deletedAt} stamp,
     * returning it to the active list.
     *
     * @param id the identifier of the contact to restore
     * @return the restored contact as a {@link ContactResponse}
     * @throws ResourceNotFoundException if no contact exists with the given id
     */
    @Transactional
    public ContactResponse restore(Long id) {
        Contact contact = findByIdOrThrow(id);
        contact.setDeletedAt(null);
        return ContactResponse.from(repository.save(contact));
    }

    /**
     * Permanently (hard) deletes a contact, removing the row and its associated
     * tags and photo blob. This cannot be undone.
     *
     * @param id the identifier of the contact to purge
     * @throws ResourceNotFoundException if no contact exists with the given id
     */
    @Transactional
    public void purge(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Contact not found with id: " + id);
        }
        repository.deleteById(id);
    }

    /* ------------------------------- Bulk actions ------------------------------- */

    /**
     * Soft-deletes each of the given active contacts. Missing ids and ids that
     * are already soft-deleted are skipped and not counted.
     *
     * @param ids the contact identifiers to soft-delete
     * @return the number of contacts actually soft-deleted
     */
    @Transactional
    public int bulkDelete(List<Long> ids) {
        Instant now = Instant.now();
        int affected = 0;
        for (Long id : distinctIds(ids)) {
            Contact contact = repository.findById(id).orElse(null);
            if (contact == null || contact.getDeletedAt() != null) {
                continue;
            }
            contact.setDeletedAt(now);
            repository.save(contact);
            affected++;
        }
        return affected;
    }

    /**
     * Sets the favourite flag on each of the given active contacts. Missing ids
     * and ids that are already soft-deleted are skipped and not counted.
     *
     * @param ids      the contact identifiers to update
     * @param favorite the favourite value to apply
     * @return the number of contacts actually updated
     */
    @Transactional
    public int bulkSetFavorite(List<Long> ids, boolean favorite) {
        int affected = 0;
        for (Long id : distinctIds(ids)) {
            Contact contact = repository.findById(id).orElse(null);
            if (contact == null || contact.getDeletedAt() != null) {
                continue;
            }
            contact.setFavorite(favorite);
            repository.save(contact);
            affected++;
        }
        return affected;
    }

    /**
     * Adds and/or removes tags on each of the given active contacts. Tag removal
     * is case-insensitive. Missing ids and ids that are already soft-deleted are
     * skipped and not counted; every existing active id processed counts as
     * affected.
     *
     * @param ids        the contact identifiers to update
     * @param addTags    tags to add (may be {@code null} or empty)
     * @param removeTags tags to remove, case-insensitively (may be {@code null} or empty)
     * @return the number of contacts actually processed
     */
    @Transactional
    public int bulkAddRemoveTags(List<Long> ids, Set<String> addTags, Set<String> removeTags) {
        Set<String> toAdd = (addTags == null) ? Set.of() : addTags;
        Set<String> removeLower = new LinkedHashSet<>();
        if (removeTags != null) {
            for (String t : removeTags) {
                if (t != null) {
                    removeLower.add(t.toLowerCase(Locale.ROOT));
                }
            }
        }
        int affected = 0;
        for (Long id : distinctIds(ids)) {
            Contact contact = repository.findById(id).orElse(null);
            if (contact == null || contact.getDeletedAt() != null) {
                continue;
            }
            Set<String> tags = new LinkedHashSet<>(contact.getTags());
            for (String t : toAdd) {
                if (t != null && !t.isBlank()) {
                    tags.add(t.trim());
                }
            }
            if (!removeLower.isEmpty()) {
                tags.removeIf(t -> removeLower.contains(t.toLowerCase(Locale.ROOT)));
            }
            contact.setTags(tags);
            repository.save(contact);
            affected++;
        }
        return affected;
    }

    /**
     * Returns the distinct, non-null ids from the given list, preserving order.
     * A {@code null} list yields an empty list so bulk operations safely no-op.
     */
    private List<Long> distinctIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(id -> id != null).distinct().toList();
    }

    /**
     * Throws a {@link StaleResourceException} when a client-supplied version is
     * present and does not match the entity's current version. A {@code null}
     * requested version skips the check (opt-in optimistic concurrency).
     *
     * @param requestedVersion the version sent by the client, or {@code null}
     * @param contact          the entity being modified
     * @throws StaleResourceException if the versions differ
     */
    private void checkVersion(Long requestedVersion, Contact contact) {
        if (requestedVersion != null && requestedVersion != contact.getVersion()) {
            throw new StaleResourceException(
                    "Contact was modified by someone else; reload and try again");
        }
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
        contact.setTags(req.tags());
        contact.setFavorite(req.favorite());
        contact.setNotes(req.notes());
    }
}
