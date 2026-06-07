package com.example.contacts;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.contacts.dto.ContactPatchRequest;
import com.example.contacts.dto.ContactRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-stack integration tests exercising the controller, service, repository
 * and a real in-memory H2 database (seeded by {@code data.sql} with three rows).
 *
 * <p>Tests are written to be independent and order-agnostic: each mutation uses
 * a unique email, count assertions use {@code >=}, and no test depends on
 * deleting any of the seeded rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContactApiIntegrationTest {

    /**
     * A minimal but valid 1x1 PNG, base64-encoded. Decoded inline so the photo
     * lifecycle test can upload real image bytes without a fixture file.
     */
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgYGAAAAAEAAH2FzhVAAAAAElFTkSuQmCC");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Generates an email guaranteed not to collide with seeds or other tests. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void fullCrudLifecycle_withUniqueEmail() throws Exception {
        String email = uniqueEmail();
        ContactRequest create = new ContactRequest("Grace", "Hopper", email,
                "+1 (555) 000-1111", "US Navy", null, false, null, null);

        // POST -> 201
        MvcResult created = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").value(email))
                .andReturn();

        long id = readId(created);

        // GET -> 200
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Grace"))
                .andExpect(jsonPath("$.email").value(email));

        // PUT (full replace) -> 200
        ContactRequest replace = new ContactRequest("Grace", "Murray Hopper", email,
                "+1 (555) 222-3333", "USN", null, false, null, null);
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replace)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Murray Hopper"))
                .andExpect(jsonPath("$.company").value("USN"));

        // PATCH (partial) -> 200
        ContactPatchRequest patch = new ContactPatchRequest(null, null, null, null, "Yale", null, null, null, null);
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company").value("Yale"))
                // unchanged field preserved
                .andExpect(jsonPath("$.lastName").value("Murray Hopper"));

        // DELETE -> 204
        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        // GET after delete -> 404
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_duplicateSeededEmail_returns409() throws Exception {
        ContactRequest duplicate = new ContactRequest("Janet", "Doe", "jane.doe@example.com",
                null, null, null, false, null, null);

        mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void getList_returnsAtLeastTheSeededRows() throws Exception {
        mockMvc.perform(get("/api/v1/contacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(3)));
    }

    @Test
    void getList_searchByCompany_returnsAtLeastOneResult() throws Exception {
        // 'Acme Ltd' is seeded; search is case-insensitive substring across company.
        mockMvc.perform(get("/api/v1/contacts").param("search", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[0].company").exists());
    }

    @Test
    void getList_searchByPhone_returnsMatchingContact() throws Exception {
        // John Smith is seeded with phone '+44 20 7946 0958'; search spans the phone field.
        mockMvc.perform(get("/api/v1/contacts").param("search", "7946"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[0].lastName").value("Smith"));
    }

    @Test
    void photoLifecycle_uploadServeAndDelete() throws Exception {
        String email = uniqueEmail();
        ContactRequest create = new ContactRequest("Photo", "Subject", email,
                null, null, null, false, null, null);

        MvcResult created = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                // a freshly created contact has no photo yet
                .andExpect(jsonPath("$.photoUrl").doesNotExist())
                .andReturn();

        long id = readId(created);

        // GET photo before any upload -> 404
        mockMvc.perform(get("/api/v1/contacts/{id}/photo", id))
                .andExpect(status().isNotFound());

        // Upload a valid PNG -> 200, response carries the versioned photoUrl
        MockMultipartFile png = new MockMultipartFile(
                "file", "tiny.png", "image/png", TINY_PNG);
        mockMvc.perform(multipart("/api/v1/contacts/{id}/photo", id).file(png))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUrl").value("/api/v1/contacts/" + id + "/photo"));

        // Serve the photo -> 200, image/png, non-empty body
        mockMvc.perform(get("/api/v1/contacts/{id}/photo", id))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(TINY_PNG));

        // Upload an unsupported type -> 400
        MockMultipartFile txt = new MockMultipartFile(
                "file", "note.txt", "text/plain", "not an image".getBytes());
        mockMvc.perform(multipart("/api/v1/contacts/{id}/photo", id).file(txt))
                .andExpect(status().isBadRequest());

        // Delete the photo -> 204
        mockMvc.perform(delete("/api/v1/contacts/{id}/photo", id))
                .andExpect(status().isNoContent());

        // Photo gone -> 404
        mockMvc.perform(get("/api/v1/contacts/{id}/photo", id))
                .andExpect(status().isNotFound());

        // ...and the contact's photoUrl is cleared again
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUrl").doesNotExist());
    }

    @Test
    void getPhoto_contactWithNoPhoto_returns404() throws Exception {
        String email = uniqueEmail();
        ContactRequest create = new ContactRequest("No", "Photo", email, null, null, null, false, null, null);

        MvcResult created = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andReturn();

        long id = readId(created);

        mockMvc.perform(get("/api/v1/contacts/{id}/photo", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", Matchers.containsString("No photo")));
    }

    @Test
    void tags_assignFilterAndList() throws Exception {
        String email = uniqueEmail();
        ContactRequest create = new ContactRequest("Linus", "Torvalds", email,
                null, "Linux Foundation", Set.of("Work", "Open Source"), false, null, null);

        // Create with tags -> the response echoes them back.
        mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tags", Matchers.hasItem("Work")))
                .andExpect(jsonPath("$.tags", Matchers.hasItem("Open Source")));

        // The new tag appears in the distinct tags listing.
        mockMvc.perform(get("/api/v1/contacts/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasItem("Open Source")));

        // Filtering by the (unique) tag returns this contact.
        mockMvc.perform(get("/api/v1/contacts").param("tag", "Open Source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[0].email").value(email));

        // Combined tag + search: a matching term keeps it; a non-matching term drops it.
        mockMvc.perform(get("/api/v1/contacts")
                        .param("tag", "Open Source").param("search", "Torvalds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)));
        mockMvc.perform(get("/api/v1/contacts")
                        .param("tag", "Open Source").param("search", "zzz-no-match"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void favorite_pinsToTopAndTogglesViaPatch() throws Exception {
        String email = uniqueEmail();
        // lastName "AaaFav" sorts before the seeded favourite (Smith), so once
        // favourites are pinned this contact must be the very first row.
        ContactRequest create = new ContactRequest("Aaa", "AaaFav", email,
                null, null, null, true, null, null);

        MvcResult created = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.favorite").value(true))
                .andReturn();
        long id = readId(created);

        // Default sort is lastName asc; a favourite is pinned above everyone else.
        mockMvc.perform(get("/api/v1/contacts").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value(email))
                .andExpect(jsonPath("$.content[0].favorite").value(true));

        // Un-favourite via PATCH -> favorite=false.
        ContactPatchRequest unfav =
                new ContactPatchRequest(null, null, null, null, null, null, false, null, null);
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unfav)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(false));
    }

    @Test
    void importCsv_createsNewRowsAndSkipsDuplicates() throws Exception {
        String u1 = uniqueEmail();
        String u2 = uniqueEmail();
        String csv = "firstName,lastName,email,phone,company,tags,favorite\n"
                + "Alan,Turing," + u1 + ",,Bletchley,Work;Friend,true\n"
                + "Katherine,Johnson," + u2 + ",,NASA,,false\n"
                + "Janet,Doe,jane.doe@example.com,,Acme,,false\n"; // seeded email -> skipped
        MockMultipartFile file = new MockMultipartFile(
                "file", "contacts.csv", "text/csv",
                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/contacts/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(1));

        // The imported contact is retrievable and kept its tags + favourite flag.
        mockMvc.perform(get("/api/v1/contacts").param("search", u1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].firstName").value("Alan"))
                .andExpect(jsonPath("$.content[0].favorite").value(true))
                .andExpect(jsonPath("$.content[0].tags", Matchers.hasItem("Work")));
    }

    @Test
    void importCsv_wrongFileType_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.pdf", "application/pdf", "not a csv".getBytes());

        mockMvc.perform(multipart("/api/v1/contacts/import").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportCsv_returnsCsvWithHeader() throws Exception {
        mockMvc.perform(get("/api/v1/contacts/export.csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(Matchers.containsString("firstName,lastName,email")))
                .andExpect(content().string(Matchers.containsString("jane.doe@example.com")));
    }

    @Test
    void exportJson_returnsArrayOfContacts() throws Exception {
        mockMvc.perform(get("/api/v1/contacts/export.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].email").exists());
    }

    @Test
    void notes_persistOnCreateAndUpdateViaPatch() throws Exception {
        String email = uniqueEmail();
        ContactRequest create = new ContactRequest("Note", "Taker", email,
                null, null, null, false, "met at Timeleft Coffee, into hiking", null);

        MvcResult created = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notes").value("met at Timeleft Coffee, into hiking"))
                .andReturn();
        long id = readId(created);

        ContactPatchRequest patch =
                new ContactPatchRequest(null, null, null, null, null, null, null, "updated note", null);
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("updated note"));
    }

    // ---- Soft delete / trash / restore / purge ---------------------------

    @Test
    void softDelete_hidesFromListButShowsInTrash() throws Exception {
        // Unique company so the active-list search matches only this contact.
        String company = "ZzxHideCo" + UUID.randomUUID().toString().substring(0, 8);
        long id = createContact("Soft", "Deleted", uniqueEmail(), company);

        // Visible in the active list before deletion.
        mockMvc.perform(get("/api/v1/contacts").param("search", company))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // DELETE -> 204 (soft delete)
        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        // Hidden from the active list afterwards.
        mockMvc.perform(get("/api/v1/contacts").param("search", company))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // Present in the trash listing.
        org.assertj.core.api.Assertions.assertThat(trashContainsId(id)).isTrue();
    }

    @Test
    void softDelete_excludedFromSearch() throws Exception {
        String email = uniqueEmail();
        // A unique, searchable company so the search matches only this contact.
        String company = "ZzxSoftDelCo" + UUID.randomUUID().toString().substring(0, 8);
        long id = createContact("Findable", "Person", email, company);

        // Before deletion the search finds it.
        mockMvc.perform(get("/api/v1/contacts").param("search", company))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        // After soft delete the search no longer returns it.
        mockMvc.perform(get("/api/v1/contacts").param("search", company))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void trash_entryCarriesNonNullDeletedAt() throws Exception {
        long id = createContact("Trash", "Stamp", uniqueEmail(), null);
        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        MvcResult trash = mockMvc.perform(get("/api/v1/contacts/trash").param("size", "200"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = objectMapper.readTree(trash.getResponse().getContentAsString())
                .get("content");
        JsonNode entry = findById(content, id);
        org.assertj.core.api.Assertions.assertThat(entry).isNotNull();
        org.assertj.core.api.Assertions.assertThat(entry.get("deletedAt").isNull()).isFalse();
    }

    @Test
    void restore_returnsContactToListAndRemovesFromTrash() throws Exception {
        String company = "ZzxRestoreCo" + UUID.randomUUID().toString().substring(0, 8);
        long id = createContact("Restore", "Me", uniqueEmail(), company);

        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        // POST /{id}/restore -> 200, body has deletedAt == null
        mockMvc.perform(post("/api/v1/contacts/{id}/restore", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) id))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());

        // Reappears in the active list.
        mockMvc.perform(get("/api/v1/contacts").param("search", company))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // ...and is gone from the trash listing.
        org.assertj.core.api.Assertions.assertThat(trashContainsId(id)).isFalse();
    }

    @Test
    void permanentDelete_purgesRowAndSubsequentGetReturns404() throws Exception {
        long id = createContact("Purge", "Forever", uniqueEmail(), null);

        // Soft delete first, then purge from trash.
        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/contacts/{id}/permanent", id))
                .andExpect(status().isNoContent());

        // Truly gone: GET -> 404 and absent from trash.
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(trashContainsId(id)).isFalse();
    }

    @Test
    void restore_missingId_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/contacts/{id}/restore", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void permanentDelete_missingId_returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/contacts/{id}/permanent", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void export_excludesSoftDeletedContacts() throws Exception {
        String company = "ZzxExportCo" + UUID.randomUUID().toString().substring(0, 8);
        long id = createContact("Export", "Excluded", uniqueEmail(), company);

        // Present in JSON export before deletion.
        mockMvc.perform(get("/api/v1/contacts/export.json"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString(company)));

        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        // Excluded from both JSON and CSV exports after soft delete.
        mockMvc.perform(get("/api/v1/contacts/export.json"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString(company))));
        mockMvc.perform(get("/api/v1/contacts/export.csv"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString(company))));
    }

    @Test
    void tags_onlyHeldByTrashedContact_disappearFromTagsListing() throws Exception {
        String tag = "ZzxUniqueTag" + UUID.randomUUID().toString().substring(0, 8);
        ContactRequest create = new ContactRequest("Tag", "Owner", uniqueEmail(),
                null, null, Set.of(tag), false, null, null);
        MvcResult created = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = readId(created);

        // The tag is listed while the contact is active.
        mockMvc.perform(get("/api/v1/contacts/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasItem(tag)));

        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        // Once trashed, the tag (used only by it) is no longer offered.
        mockMvc.perform(get("/api/v1/contacts/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.not(Matchers.hasItem(tag))));
    }

    @Test
    void create_withEmailOfTrashedContact_stillReturns409() throws Exception {
        String email = uniqueEmail();
        long id = createContact("Keeps", "Email", email, null);
        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        // A trashed contact keeps its unique email, so reuse is rejected.
        ContactRequest dup = new ContactRequest("New", "Person", email,
                null, null, null, false, null, null);
        mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dup)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // ---- Bulk actions -----------------------------------------------------

    @Test
    void bulkDelete_softDeletesAndReportsAffectedSkippingMissingAndDeleted() throws Exception {
        long a = createContact("Bulk", "DelA", uniqueEmail(), null);
        long b = createContact("Bulk", "DelB", uniqueEmail(), null);
        // Pre-soft-delete b so it is skipped by the bulk delete.
        mockMvc.perform(delete("/api/v1/contacts/{id}", b))
                .andExpect(status().isNoContent());

        // ids: a (active), b (already trashed), 999999 (missing) -> only a counts.
        String body = "{\"ids\":[" + a + "," + b + ",999999]}";
        mockMvc.perform(post("/api/v1/contacts/bulk/delete")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(1));

        // a is now in the trash too.
        org.assertj.core.api.Assertions.assertThat(trashContainsId(a)).isTrue();
    }

    @Test
    void bulkFavorite_setsAndUnsetsAndReportsAffected() throws Exception {
        long a = createContact("Bulk", "FavA", uniqueEmail(), null);
        long b = createContact("Bulk", "FavB", uniqueEmail(), null);

        String setBody = "{\"ids\":[" + a + "," + b + "],\"favorite\":true}";
        mockMvc.perform(post("/api/v1/contacts/bulk/favorite")
                        .contentType(MediaType.APPLICATION_JSON).content(setBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(2));

        mockMvc.perform(get("/api/v1/contacts/{id}", a))
                .andExpect(jsonPath("$.favorite").value(true));

        String unsetBody = "{\"ids\":[" + a + "," + b + "],\"favorite\":false}";
        mockMvc.perform(post("/api/v1/contacts/bulk/favorite")
                        .contentType(MediaType.APPLICATION_JSON).content(unsetBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(2));

        mockMvc.perform(get("/api/v1/contacts/{id}", a))
                .andExpect(jsonPath("$.favorite").value(false));
    }

    @Test
    void bulkTags_addsAndRemovesAndReportsAffected() throws Exception {
        String keep = "ZzxKeep" + UUID.randomUUID().toString().substring(0, 6);
        String drop = "ZzxDrop" + UUID.randomUUID().toString().substring(0, 6);
        ContactRequest create = new ContactRequest("Bulk", "Tagger", uniqueEmail(),
                null, null, Set.of(drop), false, null, null);
        MvcResult created = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = readId(created);

        String body = "{\"ids\":[" + id + "],\"addTags\":[\"" + keep + "\"],\"removeTags\":[\"" + drop + "\"]}";
        mockMvc.perform(post("/api/v1/contacts/bulk/tags")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(1));

        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(jsonPath("$.tags", Matchers.hasItem(keep)))
                .andExpect(jsonPath("$.tags", Matchers.not(Matchers.hasItem(drop))));
    }

    @Test
    void bulk_emptyIds_returnsZeroAffected() throws Exception {
        mockMvc.perform(post("/api/v1/contacts/bulk/delete")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(0));
    }

    // ---- Optimistic concurrency (412) ------------------------------------

    @Test
    void update_staleVersion_returns412_correctVersionSucceedsAndIncrements() throws Exception {
        String email = uniqueEmail();
        MvcResult created = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ContactRequest(
                                "Ver", "Sion", email, null, null, null, false, null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        long id = readId(created);

        // PUT with a wrong version -> 412.
        ContactRequest stale = new ContactRequest("Ver", "Sion", email,
                null, null, null, false, null, 99L);
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(stale)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.status").value(412));

        // PUT with the correct (current) version -> 200, version increments.
        ContactRequest fresh = new ContactRequest("Ver", "SionUpdated", email,
                null, null, null, false, null, 0L);
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("SionUpdated"))
                .andExpect(jsonPath("$.version").value(1));

        // Confirm via a fresh read (new transaction) that the version persisted.
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void patch_staleVersion_returns412_nullVersionSkipsCheck() throws Exception {
        String email = uniqueEmail();
        long id = createContact("Patch", "Ver", email, null);

        // PATCH with a wrong version -> 412.
        ContactPatchRequest stale =
                new ContactPatchRequest(null, null, null, null, "X", null, null, null, 77L);
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(stale)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.status").value(412));

        // PATCH with null version -> check skipped, succeeds.
        ContactPatchRequest skip =
                new ContactPatchRequest(null, null, null, null, "Applied", null, null, null, null);
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(skip)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company").value("Applied"));
    }

    @Test
    void response_carriesVersionStartingAtZero() throws Exception {
        mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ContactRequest(
                                "Zero", "Version", uniqueEmail(), null, null, null, false, null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0));
    }

    // ---- Helpers ----------------------------------------------------------

    /** Creates a contact via the API and returns its generated id. */
    private long createContact(String first, String last, String email, String company)
            throws Exception {
        ContactRequest req = new ContactRequest(first, last, email,
                null, company, null, false, null, null);
        MvcResult result = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return readId(result);
    }

    /** Returns whether the {@code /trash} listing currently contains the given id. */
    private boolean trashContainsId(long id) throws Exception {
        MvcResult trash = mockMvc.perform(get("/api/v1/contacts/trash").param("size", "500"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(trash.getResponse().getContentAsString())
                .get("content");
        return findById(content, id) != null;
    }

    /** Finds the JSON node in an array whose {@code id} equals the given id, or null. */
    private JsonNode findById(JsonNode array, long id) {
        if (array == null) {
            return null;
        }
        for (JsonNode node : array) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }

    /** Extracts the {@code id} from a JSON response body. */
    private long readId(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }
}
