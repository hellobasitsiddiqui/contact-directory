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
                "+1 (555) 000-1111", "US Navy");

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
                "+1 (555) 222-3333", "USN");
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replace)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Murray Hopper"))
                .andExpect(jsonPath("$.company").value("USN"));

        // PATCH (partial) -> 200
        ContactPatchRequest patch = new ContactPatchRequest(null, null, null, null, "Yale");
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
                null, null);

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
    void photoLifecycle_uploadServeAndDelete() throws Exception {
        String email = uniqueEmail();
        ContactRequest create = new ContactRequest("Photo", "Subject", email,
                null, null);

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
        ContactRequest create = new ContactRequest("No", "Photo", email, null, null);

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

    /** Extracts the {@code id} from a JSON response body. */
    private long readId(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }
}
