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
                "+1 (555) 000-1111", "US Navy", null, false, null);

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
                "+1 (555) 222-3333", "USN", null, false, null);
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replace)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Murray Hopper"))
                .andExpect(jsonPath("$.company").value("USN"));

        // PATCH (partial) -> 200
        ContactPatchRequest patch = new ContactPatchRequest(null, null, null, null, "Yale", null, null, null);
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
                null, null, null, false, null);

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
                null, null, null, false, null);

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
        ContactRequest create = new ContactRequest("No", "Photo", email, null, null, null, false, null);

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
                null, "Linux Foundation", Set.of("Work", "Open Source"), false, null);

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
                null, null, null, true, null);

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
                new ContactPatchRequest(null, null, null, null, null, null, false, null);
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
                null, null, null, false, "met at Timeleft Coffee, into hiking");

        MvcResult created = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notes").value("met at Timeleft Coffee, into hiking"))
                .andReturn();
        long id = readId(created);

        ContactPatchRequest patch =
                new ContactPatchRequest(null, null, null, null, null, null, null, "updated note");
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("updated note"));
    }

    /** Extracts the {@code id} from a JSON response body. */
    private long readId(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }
}
