package com.example.contacts.controller;

import com.example.contacts.dto.BulkFavoriteRequest;
import com.example.contacts.dto.BulkIdsRequest;
import com.example.contacts.dto.BulkResult;
import com.example.contacts.dto.BulkTagsRequest;
import com.example.contacts.dto.ContactPatchRequest;
import com.example.contacts.dto.ContactRequest;
import com.example.contacts.dto.ContactResponse;
import com.example.contacts.dto.ImportSummary;
import com.example.contacts.exception.InvalidFileException;
import com.example.contacts.exception.InvalidImageException;
import com.example.contacts.service.ContactService;
import com.example.contacts.service.PhotoData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import jakarta.validation.Valid;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * REST controller exposing CRUD and search operations for the Contact Directory.
 *
 * <p>All endpoints are rooted at {@code /api/v1/contacts} and delegate business logic to
 * {@link ContactService}. Responses are wrapped in {@link ResponseEntity} so that status
 * codes and headers (notably the {@code Location} header on creation) are set explicitly.
 */
@RestController
@RequestMapping("/api/v1/contacts")
@Tag(name = "Contacts", description = "Create, read, update, delete and search contacts")
public class ContactController {

    private final ContactService contactService;

    /**
     * Creates the controller with its required {@link ContactService} collaborator.
     *
     * @param contactService the service handling contact business logic
     */
    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    /**
     * Creates a new contact.
     *
     * @param request the contact payload to persist (validated)
     * @return {@code 201 Created} with the created {@link ContactResponse} body and a
     *         {@code Location} header pointing at the new resource
     */
    @PostMapping
    @Operation(summary = "Create a contact",
            description = "Creates a new contact and returns it with a Location header.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Contact created"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
            @ApiResponse(responseCode = "409", description = "Email already exists", content = @Content)
    })
    public ResponseEntity<ContactResponse> create(@Valid @RequestBody ContactRequest request) {
        ContactResponse created = contactService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Lists contacts, optionally filtered by a free-text search term.
     *
     * @param search   optional case-insensitive term matched against name, email and company
     * @param pageable pagination and sorting (defaults to size 20, sorted by {@code lastName})
     * @return {@code 200 OK} with a page of {@link ContactResponse}
     */
    @GetMapping
    @Operation(summary = "List contacts",
            description = "Returns a paginated list of contacts, optionally filtered by a search term and/or a tag.")
    @ApiResponse(responseCode = "200", description = "Page of contacts")
    public ResponseEntity<Page<ContactResponse>> list(
            @RequestParam(required = false)
            @Parameter(description = "Free-text search across first name, last name, email, company and phone")
            String search,
            @RequestParam(required = false)
            @Parameter(description = "Restrict results to contacts carrying this tag (case-insensitive)")
            String tag,
            @ParameterObject @PageableDefault(size = 20, sort = "lastName") Pageable pageable) {
        return ResponseEntity.ok(contactService.list(search, tag, pageable));
    }

    /**
     * Returns the distinct set of tags currently in use, sorted, for populating
     * the tag filter control.
     *
     * @return {@code 200 OK} with the list of distinct tags
     */
    @GetMapping("/tags")
    @Operation(summary = "List all tags in use",
            description = "Returns the distinct, sorted set of tags assigned to any contact.")
    @ApiResponse(responseCode = "200", description = "List of tags")
    public ResponseEntity<List<String>> listTags() {
        return ResponseEntity.ok(contactService.listTags());
    }

    /**
     * Retrieves a single contact by id.
     *
     * @param id the contact identifier
     * @return {@code 200 OK} with the matching {@link ContactResponse}
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a contact by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contact found"),
            @ApiResponse(responseCode = "404", description = "Contact not found", content = @Content)
    })
    public ResponseEntity<ContactResponse> get(
            @PathVariable @Parameter(description = "Contact identifier") Long id) {
        return ResponseEntity.ok(contactService.get(id));
    }

    /**
     * Fully replaces an existing contact.
     *
     * @param id      the contact identifier
     * @param request the replacement payload (validated)
     * @return {@code 200 OK} with the updated {@link ContactResponse}
     */
    @PutMapping("/{id}")
    @Operation(summary = "Replace a contact",
            description = "Performs a full update of the contact with the given id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contact updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
            @ApiResponse(responseCode = "404", description = "Contact not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "Email already exists", content = @Content),
            @ApiResponse(responseCode = "412", description = "Stale version / concurrent modification", content = @Content)
    })
    public ResponseEntity<ContactResponse> update(
            @PathVariable @Parameter(description = "Contact identifier") Long id,
            @Valid @RequestBody ContactRequest request) {
        return ResponseEntity.ok(contactService.update(id, request));
    }

    /**
     * Partially updates an existing contact; only non-null fields are applied.
     *
     * @param id      the contact identifier
     * @param request the partial payload (validated)
     * @return {@code 200 OK} with the updated {@link ContactResponse}
     */
    @PatchMapping("/{id}")
    @Operation(summary = "Partially update a contact",
            description = "Updates only the supplied (non-null) fields of the contact.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contact updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
            @ApiResponse(responseCode = "404", description = "Contact not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "Email already exists", content = @Content),
            @ApiResponse(responseCode = "412", description = "Stale version / concurrent modification", content = @Content)
    })
    public ResponseEntity<ContactResponse> patch(
            @PathVariable @Parameter(description = "Contact identifier") Long id,
            @Valid @RequestBody ContactPatchRequest request) {
        return ResponseEntity.ok(contactService.patch(id, request));
    }

    /**
     * Soft-deletes a contact by id, moving it to the trash. The row is retained
     * (stamped with a deletion timestamp) and can later be restored or purged.
     *
     * @param id the contact identifier
     * @return {@code 204 No Content}
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a contact",
            description = "Soft-deletes the contact (moves it to trash); it can be restored or permanently deleted later.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contact moved to trash"),
            @ApiResponse(responseCode = "404", description = "Contact not found", content = @Content)
    })
    public ResponseEntity<Void> delete(
            @PathVariable @Parameter(description = "Contact identifier") Long id) {
        contactService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lists soft-deleted (trashed) contacts.
     *
     * @param pageable pagination and sorting (defaults to size 20, sorted by {@code deletedAt})
     * @return {@code 200 OK} with a page of soft-deleted {@link ContactResponse}
     */
    @GetMapping("/trash")
    @Operation(summary = "List trashed contacts",
            description = "Returns a paginated list of soft-deleted contacts.")
    @ApiResponse(responseCode = "200", description = "Page of trashed contacts")
    public ResponseEntity<Page<ContactResponse>> listTrash(
            @ParameterObject @PageableDefault(size = 20, sort = "deletedAt") Pageable pageable) {
        return ResponseEntity.ok(contactService.listTrash(pageable));
    }

    /**
     * Restores a soft-deleted contact, clearing its deletion timestamp.
     *
     * @param id the contact identifier
     * @return {@code 200 OK} with the restored {@link ContactResponse}
     */
    @PostMapping("/{id}/restore")
    @Operation(summary = "Restore a trashed contact",
            description = "Restores a soft-deleted contact, returning it to the active list.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contact restored"),
            @ApiResponse(responseCode = "404", description = "Contact not found", content = @Content)
    })
    public ResponseEntity<ContactResponse> restore(
            @PathVariable @Parameter(description = "Contact identifier") Long id) {
        return ResponseEntity.ok(contactService.restore(id));
    }

    /**
     * Permanently deletes a contact, removing the row entirely.
     *
     * @param id the contact identifier
     * @return {@code 204 No Content}
     */
    @DeleteMapping("/{id}/permanent")
    @Operation(summary = "Permanently delete a contact",
            description = "Hard-deletes the contact, removing it irreversibly.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contact permanently deleted"),
            @ApiResponse(responseCode = "404", description = "Contact not found", content = @Content)
    })
    public ResponseEntity<Void> purge(
            @PathVariable @Parameter(description = "Contact identifier") Long id) {
        contactService.purge(id);
        return ResponseEntity.noContent().build();
    }

    /** Maximum allowed photo size in bytes (2MB). */
    private static final long MAX_PHOTO_SIZE = 2L * 1024 * 1024;

    /** Image content types accepted for contact photos (matched case-insensitively). */
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp");

    /**
     * Returns the photo bytes for a contact.
     *
     * @param id the contact identifier
     * @return {@code 200 OK} with the image bytes and the stored content type
     */
    @GetMapping("/{id}/photo")
    @Operation(summary = "Get a contact's photo",
            description = "Returns the contact's photo bytes with the stored content type.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Photo found"),
            @ApiResponse(responseCode = "404", description = "Contact or photo not found", content = @Content)
    })
    public ResponseEntity<byte[]> getPhoto(
            @PathVariable @Parameter(description = "Contact identifier") Long id) {
        PhotoData photo = contactService.getPhoto(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .body(photo.data());
    }

    /**
     * Uploads (creates or replaces) the photo for a contact.
     *
     * @param id   the contact identifier
     * @param file the multipart image file (field name {@code file})
     * @return {@code 200 OK} with the updated {@link ContactResponse} (including its {@code photoUrl})
     * @throws IOException if the uploaded file bytes cannot be read
     */
    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a contact's photo",
            description = "Uploads an image (PNG, JPEG, GIF or WEBP, max 2MB) as the contact's photo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Photo uploaded"),
            @ApiResponse(responseCode = "400", description = "Invalid image", content = @Content),
            @ApiResponse(responseCode = "404", description = "Contact not found", content = @Content),
            @ApiResponse(responseCode = "413", description = "File too large", content = @Content)
    })
    public ResponseEntity<ContactResponse> uploadPhoto(
            @PathVariable @Parameter(description = "Contact identifier") Long id,
            @RequestParam("file") @Parameter(description = "Image file to upload") MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new InvalidImageException("No file provided");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new InvalidImageException(
                    "Unsupported image type: " + contentType + ". Allowed: PNG, JPEG, GIF, WEBP");
        }
        if (file.getSize() > MAX_PHOTO_SIZE) {
            throw new InvalidImageException("Image too large (max 2MB)");
        }
        contactService.savePhoto(id, file.getBytes(), file.getContentType());
        return ResponseEntity.ok(contactService.get(id));
    }

    /**
     * Deletes the photo for a contact.
     *
     * @param id the contact identifier
     * @return {@code 204 No Content}
     */
    @DeleteMapping("/{id}/photo")
    @Operation(summary = "Delete a contact's photo")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Photo deleted"),
            @ApiResponse(responseCode = "404", description = "Contact not found", content = @Content)
    })
    public ResponseEntity<Void> deletePhoto(
            @PathVariable @Parameter(description = "Contact identifier") Long id) {
        contactService.deletePhoto(id);
        return ResponseEntity.noContent().build();
    }

    /** Content types browsers commonly send for a .csv upload. */
    private static final Set<String> CSV_CONTENT_TYPES = Set.of(
            "text/csv", "application/csv", "application/vnd.ms-excel", "text/plain");

    /**
     * Exports all contacts as a downloadable CSV file.
     *
     * @return {@code 200 OK} with a {@code text/csv} body and an attachment disposition
     */
    @GetMapping(value = "/export.csv", produces = "text/csv")
    @Operation(summary = "Export all contacts as CSV")
    @ApiResponse(responseCode = "200", description = "CSV document")
    public ResponseEntity<byte[]> exportCsv() {
        byte[] body = contactService.exportCsv().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contacts.csv\"")
                .body(body);
    }

    /**
     * Exports all contacts as a downloadable JSON array.
     *
     * @return {@code 200 OK} with a JSON body and an attachment disposition
     */
    @GetMapping(value = "/export.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Export all contacts as JSON")
    @ApiResponse(responseCode = "200", description = "JSON array of contacts")
    public ResponseEntity<List<ContactResponse>> exportJson() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contacts.json\"")
                .body(contactService.exportAll());
    }

    /**
     * Bulk-imports contacts from an uploaded CSV file.
     *
     * @param file the multipart CSV file (field name {@code file})
     * @return {@code 200 OK} with an {@link ImportSummary}
     * @throws IOException if the uploaded bytes cannot be read
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import contacts from a CSV file",
            description = "Uploads a CSV (optional header row) and creates contacts, "
                    + "skipping rows whose email already exists.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Import summary"),
            @ApiResponse(responseCode = "400", description = "Invalid file", content = @Content),
            @ApiResponse(responseCode = "413", description = "File too large", content = @Content)
    })
    public ResponseEntity<ImportSummary> importCsv(
            @RequestParam("file") @Parameter(description = "CSV file to import") MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("No file provided");
        }
        String name = file.getOriginalFilename();
        String type = file.getContentType();
        boolean isCsv = (type != null && CSV_CONTENT_TYPES.contains(type.toLowerCase()))
                || (name != null && name.toLowerCase().endsWith(".csv"));
        if (!isCsv) {
            throw new InvalidFileException(
                    "Unsupported file type: " + type + ". Please upload a .csv file.");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return ResponseEntity.ok(contactService.importCsv(content));
    }

    /**
     * Soft-deletes multiple contacts in one request.
     *
     * @param request the bulk request carrying the contact ids to delete
     * @return {@code 200 OK} with a {@link BulkResult} reporting how many were affected
     */
    @PostMapping("/bulk/delete")
    @Operation(summary = "Bulk soft-delete contacts",
            description = "Soft-deletes the supplied contacts, skipping missing or already-deleted ids.")
    @ApiResponse(responseCode = "200", description = "Bulk delete result")
    public ResponseEntity<BulkResult> bulkDelete(@RequestBody BulkIdsRequest request) {
        int affected = contactService.bulkDelete(request.ids());
        return ResponseEntity.ok(new BulkResult(affected));
    }

    /**
     * Sets the favorite flag on multiple contacts in one request.
     *
     * @param request the bulk request carrying the contact ids and the desired favorite state
     * @return {@code 200 OK} with a {@link BulkResult} reporting how many were affected
     */
    @PostMapping("/bulk/favorite")
    @Operation(summary = "Bulk set favorite on contacts",
            description = "Sets the favorite flag on the supplied contacts, skipping missing or already-deleted ids.")
    @ApiResponse(responseCode = "200", description = "Bulk favorite result")
    public ResponseEntity<BulkResult> bulkFavorite(@RequestBody BulkFavoriteRequest request) {
        int affected = contactService.bulkSetFavorite(request.ids(), request.favorite());
        return ResponseEntity.ok(new BulkResult(affected));
    }

    /**
     * Adds and/or removes tags across multiple contacts in one request.
     *
     * @param request the bulk request carrying the contact ids and the tags to add/remove
     * @return {@code 200 OK} with a {@link BulkResult} reporting how many were affected
     */
    @PostMapping("/bulk/tags")
    @Operation(summary = "Bulk add/remove tags on contacts",
            description = "Adds and/or removes tags across the supplied contacts, skipping missing or already-deleted ids.")
    @ApiResponse(responseCode = "200", description = "Bulk tags result")
    public ResponseEntity<BulkResult> bulkTags(@RequestBody BulkTagsRequest request) {
        int affected = contactService.bulkAddRemoveTags(
                request.ids(), request.addTags(), request.removeTags());
        return ResponseEntity.ok(new BulkResult(affected));
    }
}
