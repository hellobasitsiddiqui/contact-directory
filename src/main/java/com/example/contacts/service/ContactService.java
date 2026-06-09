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
 *
 * <p>Every operation is owner-aware. Callers pass the id of the current user
 * ({@code ownerId}) and whether that user is an administrator ({@code isAdmin}).
 * Administrators operate unscoped across all owners (the historical behaviour);
 * non-administrators are confined to the contacts they own, with a contact
 * belonging to another owner treated exactly like a missing one (so its
 * existence is never revealed).
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
     * @param ownerId  the id of the current user (used to scope non-admin queries)
     * @param isAdmin  whether the current user is an administrator (unscoped)
     * @return a page of {@link ContactResponse} projections
     */
    @Transactional(readOnly = true)
    public Page<ContactResponse> list(String search, String tag, Pageable pageable,
                                      Long ownerId, boolean isAdmin) {
        String q = (search == null) ? "" : search.trim();
        boolean hasTag = tag != null && !tag.isBlank();
        // Favourites are always pinned to the top, before the caller's sort.
        Pageable effective = withFavoritesFirst(pageable);

        Page<Contact> contacts;
        if (hasTag) {
            contacts = isAdmin
                    ? repository.searchAndFilterByTag(q, tag.trim(), effective)
                    : repository.searchAndFilterByTag(q, tag.trim(), ownerId, effective);
        } else if (q.isEmpty()) {
            contacts = isAdmin
                    ? repository.findByDeletedAtIsNull(effective)
                    : repository.findByDeletedAtIsNullAndOwnerId(ownerId, effective);
        } else {
            contacts = isAdmin
                    ? repository.search(q, effective)
                    : repository.search(q, ownerId, effective);
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
     * Returns the distinct set of tags currently in use, sorted case-insensitively.
     * Used to populate the tag filter control. Admins see tags across all owners;
     * non-admins see only the tags in use on their own contacts.
     *
     * @param ownerId the id of the current user (used to scope non-admin queries)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @return the sorted list of distinct tags
     */
    @Transactional(readOnly = true)
    public List<String> listTags(Long ownerId, boolean isAdmin) {
        // Sort here (not in SQL): Postgres rejects SELECT DISTINCT with ORDER BY
        // LOWER(t) when that expression isn't in the select list, so the repository
        // returns the distinct tags unordered and we sort case-insensitively here.
        List<String> tags = new ArrayList<>(isAdmin
                ? repository.findDistinctTags()
                : repository.findDistinctTagsByOwnerId(ownerId));
        tags.sort(String.CASE_INSENSITIVE_ORDER);
        return tags;
    }

    /* ----------------------------- Import / export ----------------------------- */

    /** CSV column order for export and the basis for header detection on import. */
    private static final List<String> CSV_COLUMNS =
            List.of("id", "firstName", "lastName", "email", "phone", "company", "tags", "favorite", "notes");

    /**
     * Returns every active (non-trashed) contact (unpaged), sorted by last then
     * first name, for a full export. Soft-deleted contacts are excluded. Admins
     * export across all owners; non-admins export only their own contacts.
     *
     * @param ownerId the id of the current user (used to scope non-admin queries)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @return all active contacts as {@link ContactResponse} projections
     */
    @Transactional(readOnly = true)
    public List<ContactResponse> exportAll(Long ownerId, boolean isAdmin) {
        Sort sort = Sort.by(Sort.Order.asc("lastName"), Sort.Order.asc("firstName"));
        List<Contact> contacts = isAdmin
                ? repository.findByDeletedAtIsNull(sort)
                : repository.findByDeletedAtIsNullAndOwnerId(ownerId, sort);
        return contacts.stream()
                .map(ContactResponse::from)
                .toList();
    }

    /**
     * Renders every contact as a CSV document (header row + one row per contact;
     * tags joined with {@code ;}). Uses CRLF line endings. Scoped to the current
     * user unless they are an administrator.
     *
     * @param ownerId the id of the current user (used to scope non-admin queries)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @return the CSV text
     */
    @Transactional(readOnly = true)
    public String exportCsv(Long ownerId, boolean isAdmin) {
        StringBuilder sb = new StringBuilder();
        sb.append(CsvSupport.toRow(CSV_COLUMNS)).append("\r\n");
        for (ContactResponse c : exportAll(ownerId, isAdmin)) {
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
     * email already exists for the importing owner are skipped; invalid rows are
     * reported in the summary. Every imported contact is owned by {@code ownerId}.
     *
     * @param content the raw CSV text
     * @param ownerId the id of the user the imported contacts will belong to
     * @return a summary of imported / skipped counts and per-row errors
     */
    @Transactional
    public ImportSummary importCsv(String content, Long ownerId) {
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
            if (repository.existsByEmailIgnoreCaseAndOwnerId(email, ownerId)) {
                skipped++;
                continue;
            }

            Contact contact = new Contact();
            contact.setOwnerId(ownerId);
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
     * @param id      the contact identifier
     * @param ownerId the id of the current user (used to scope non-admin lookups)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @return the matching {@link ContactResponse}
     * @throws ResourceNotFoundException if no contact exists with the given id
     *                                   (or it belongs to another owner and the
     *                                   caller is not an admin)
     */
    @Transactional(readOnly = true)
    public ContactResponse get(Long id, Long ownerId, boolean isAdmin) {
        Contact contact = findByIdOrThrow(id, ownerId, isAdmin);
        // A soft-deleted contact lives in the trash, not the active directory, so
        // the normal GET endpoint treats it as not found (use /trash or restore).
        if (contact.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Contact not found with id: " + id);
        }
        return ContactResponse.from(contact);
    }

    /**
     * Creates a new contact owned by the given user.
     *
     * @param req     the validated contact request body
     * @param ownerId the id of the user that will own the new contact
     * @return the persisted contact as a {@link ContactResponse}
     * @throws DuplicateEmailException if the owner already has a contact with the same email
     */
    @Transactional
    public ContactResponse create(ContactRequest req, Long ownerId) {
        if (repository.existsByEmailIgnoreCaseAndOwnerId(req.email(), ownerId)) {
            throw new DuplicateEmailException(
                    "A contact with email '" + req.email() + "' already exists");
        }
        Contact contact = new Contact();
        contact.setOwnerId(ownerId);
        applyRequest(contact, req);
        return ContactResponse.from(repository.save(contact));
    }

    /**
     * Fully replaces an existing contact with the supplied values.
     *
     * @param id      the identifier of the contact to update
     * @param req     the validated contact request body
     * @param ownerId the id of the current user (used to scope non-admin lookups)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @return the updated contact as a {@link ContactResponse}
     * @throws ResourceNotFoundException if no contact exists with the given id
     *                                   (or it belongs to another owner and the
     *                                   caller is not an admin)
     * @throws DuplicateEmailException   if another of the contact owner's contacts already uses the email
     */
    @Transactional
    public ContactResponse update(Long id, ContactRequest req, Long ownerId, boolean isAdmin) {
        Contact contact = findByIdOrThrow(id, ownerId, isAdmin);
        checkVersion(req.version(), contact);
        if (repository.existsByEmailIgnoreCaseAndOwnerIdAndIdNot(
                req.email(), contact.getOwnerId(), id)) {
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
     * @param id      the identifier of the contact to patch
     * @param req     the patch request body (all fields optional)
     * @param ownerId the id of the current user (used to scope non-admin lookups)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @return the updated contact as a {@link ContactResponse}
     * @throws ResourceNotFoundException if no contact exists with the given id
     *                                   (or it belongs to another owner and the
     *                                   caller is not an admin)
     * @throws DuplicateEmailException   if the supplied email is already in use by
     *                                   another of the contact owner's contacts
     */
    @Transactional
    public ContactResponse patch(Long id, ContactPatchRequest req, Long ownerId, boolean isAdmin) {
        Contact contact = findByIdOrThrow(id, ownerId, isAdmin);
        checkVersion(req.version(), contact);
        if (req.email() != null
                && repository.existsByEmailIgnoreCaseAndOwnerIdAndIdNot(
                        req.email(), contact.getOwnerId(), id)) {
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
     * @param id      the identifier of the contact to soft-delete
     * @param ownerId the id of the current user (used to scope non-admin lookups)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @throws ResourceNotFoundException if no contact exists with the given id
     *                                   (or it belongs to another owner and the
     *                                   caller is not an admin)
     */
    @Transactional
    public void delete(Long id, Long ownerId, boolean isAdmin) {
        Contact contact = findByIdOrThrow(id, ownerId, isAdmin);
        if (contact.getDeletedAt() == null) {
            contact.setDeletedAt(Instant.now());
            repository.save(contact);
        }
    }

    /**
     * Returns a page of soft-deleted (trashed) contacts only. Admins see trashed
     * contacts across all owners; non-admins see only their own.
     *
     * @param pageable pagination and sorting information
     * @param ownerId  the id of the current user (used to scope non-admin queries)
     * @param isAdmin  whether the current user is an administrator (unscoped)
     * @return a page of trashed contacts as {@link ContactResponse} projections
     */
    @Transactional(readOnly = true)
    public Page<ContactResponse> listTrash(Pageable pageable, Long ownerId, boolean isAdmin) {
        Page<Contact> contacts = isAdmin
                ? repository.findByDeletedAtIsNotNull(pageable)
                : repository.findByDeletedAtIsNotNullAndOwnerId(ownerId, pageable);
        return contacts.map(ContactResponse::from);
    }

    /**
     * Restores a soft-deleted contact by clearing its {@code deletedAt} stamp,
     * returning it to the active list.
     *
     * @param id      the identifier of the contact to restore
     * @param ownerId the id of the current user (used to scope non-admin lookups)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @return the restored contact as a {@link ContactResponse}
     * @throws ResourceNotFoundException if no contact exists with the given id
     *                                   (or it belongs to another owner and the
     *                                   caller is not an admin)
     */
    @Transactional
    public ContactResponse restore(Long id, Long ownerId, boolean isAdmin) {
        Contact contact = findByIdOrThrow(id, ownerId, isAdmin);
        contact.setDeletedAt(null);
        return ContactResponse.from(repository.save(contact));
    }

    /**
     * Permanently (hard) deletes a contact, removing the row and its associated
     * tags and photo blob. This cannot be undone.
     *
     * <p>This operation is admin-only at the controller layer; the owner context
     * is still honoured so a non-admin caller (should the role check be bypassed)
     * may only purge contacts they own, and a contact belonging to another owner
     * is treated as missing.
     *
     * @param id      the identifier of the contact to purge
     * @param ownerId the id of the current user (used to scope non-admin lookups)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @throws ResourceNotFoundException if no contact exists with the given id
     *                                   (or it belongs to another owner and the
     *                                   caller is not an admin)
     */
    @Transactional
    public void purge(Long id, Long ownerId, boolean isAdmin) {
        Contact contact = findByIdOrThrow(id, ownerId, isAdmin);
        repository.delete(contact);
    }

    /* ------------------------------- Bulk actions ------------------------------- */

    /**
     * Soft-deletes each of the given active contacts. Missing ids and ids that
     * are already soft-deleted are skipped and not counted. For non-admins, a
     * contact owned by another user is treated as missing.
     *
     * @param ids     the contact identifiers to soft-delete
     * @param ownerId the id of the current user (used to scope non-admin lookups)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @return the number of contacts actually soft-deleted
     */
    @Transactional
    public int bulkDelete(List<Long> ids, Long ownerId, boolean isAdmin) {
        Instant now = Instant.now();
        int affected = 0;
        for (Long id : distinctIds(ids)) {
            Contact contact = findScoped(id, ownerId, isAdmin);
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
     * and ids that are already soft-deleted are skipped and not counted. For
     * non-admins, a contact owned by another user is treated as missing.
     *
     * @param ids      the contact identifiers to update
     * @param favorite the favourite value to apply
     * @param ownerId  the id of the current user (used to scope non-admin lookups)
     * @param isAdmin  whether the current user is an administrator (unscoped)
     * @return the number of contacts actually updated
     */
    @Transactional
    public int bulkSetFavorite(List<Long> ids, boolean favorite, Long ownerId, boolean isAdmin) {
        int affected = 0;
        for (Long id : distinctIds(ids)) {
            Contact contact = findScoped(id, ownerId, isAdmin);
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
     * affected. For non-admins, a contact owned by another user is treated as
     * missing.
     *
     * @param ids        the contact identifiers to update
     * @param addTags    tags to add (may be {@code null} or empty)
     * @param removeTags tags to remove, case-insensitively (may be {@code null} or empty)
     * @param ownerId    the id of the current user (used to scope non-admin lookups)
     * @param isAdmin    whether the current user is an administrator (unscoped)
     * @return the number of contacts actually processed
     */
    @Transactional
    public int bulkAddRemoveTags(List<Long> ids, Set<String> addTags, Set<String> removeTags,
                                 Long ownerId, boolean isAdmin) {
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
            Contact contact = findScoped(id, ownerId, isAdmin);
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
     * @param id      the contact identifier
     * @param ownerId the id of the current user (used to scope non-admin lookups)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @return the photo bytes and content type wrapped in a {@link PhotoData}
     * @throws ResourceNotFoundException if no contact exists with the given id
     *                                   (or it belongs to another owner and the
     *                                   caller is not an admin), or if the contact
     *                                   has no photo
     */
    @Transactional(readOnly = true)
    public PhotoData getPhoto(Long id, Long ownerId, boolean isAdmin) {
        Contact contact = findByIdOrThrow(id, ownerId, isAdmin);
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
     * @param ownerId     the id of the current user (used to scope non-admin lookups)
     * @param isAdmin     whether the current user is an administrator (unscoped)
     * @throws ResourceNotFoundException if no contact exists with the given id
     *                                   (or it belongs to another owner and the
     *                                   caller is not an admin)
     */
    @Transactional
    public void savePhoto(Long id, byte[] data, String contentType, Long ownerId, boolean isAdmin) {
        Contact contact = findByIdOrThrow(id, ownerId, isAdmin);
        contact.setPhoto(data);
        contact.setPhotoContentType(contentType);
        repository.save(contact);
    }

    /**
     * Removes the photo from a contact, clearing both the bytes and the
     * content-type flag.
     *
     * @param id      the contact identifier
     * @param ownerId the id of the current user (used to scope non-admin lookups)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @throws ResourceNotFoundException if no contact exists with the given id
     *                                   (or it belongs to another owner and the
     *                                   caller is not an admin)
     */
    @Transactional
    public void deletePhoto(Long id, Long ownerId, boolean isAdmin) {
        Contact contact = findByIdOrThrow(id, ownerId, isAdmin);
        contact.setPhoto(null);
        contact.setPhotoContentType(null);
        repository.save(contact);
    }

    /**
     * Loads a contact by id, honouring owner scoping, or throws a
     * {@link ResourceNotFoundException}. Admins look up unscoped; non-admins are
     * confined to contacts they own, so a contact belonging to another owner is
     * reported as not found (its existence is never revealed).
     *
     * @param id      the contact identifier
     * @param ownerId the id of the current user (used to scope non-admin lookups)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @return the matching entity
     * @throws ResourceNotFoundException if no matching contact exists in scope
     */
    private Contact findByIdOrThrow(Long id, Long ownerId, boolean isAdmin) {
        return (isAdmin
                ? repository.findById(id)
                : repository.findByIdAndOwnerId(id, ownerId))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Contact not found with id: " + id));
    }

    /**
     * Loads a contact by id, honouring owner scoping, returning {@code null} when
     * none is in scope. Used by bulk operations, which silently skip contacts that
     * are missing or owned by another user (for non-admins).
     *
     * @param id      the contact identifier
     * @param ownerId the id of the current user (used to scope non-admin lookups)
     * @param isAdmin whether the current user is an administrator (unscoped)
     * @return the matching entity, or {@code null} if none is in scope
     */
    private Contact findScoped(Long id, Long ownerId, boolean isAdmin) {
        return (isAdmin
                ? repository.findById(id)
                : repository.findByIdAndOwnerId(id, ownerId))
                .orElse(null);
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
