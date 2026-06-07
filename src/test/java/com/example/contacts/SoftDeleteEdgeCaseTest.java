package com.example.contacts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.contacts.dto.ContactRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Focused, full-stack edge/negative coverage for the soft-delete / trash /
 * restore / permanent-delete pack, run against a real in-memory H2 database.
 *
 * <p>Every test creates its own contacts with UUID-unique emails (never relying
 * on deleting any of the rows seeded by {@code data.sql}). Where a test needs an
 * unambiguous list/search match it tags the contact with a unique company token,
 * so {@code totalElements} can be asserted exactly even while other tests run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class SoftDeleteEdgeCaseTest {

    /** An id far above anything the suite will ever generate, used for "missing" cases. */
    private static final long MISSING_ID = 999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Generates an email guaranteed not to collide with seeds or other tests. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /** A unique, searchable token suitable for an exact list/search match. */
    private String uniqueToken(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    // ---- (1) permanent delete of a missing id -----------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void permanentDelete_missingId_returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/contacts/{id}/permanent", MISSING_ID))
                .andExpect(status().isNotFound());
    }

    // ---- (2) restore of a never-deleted contact ---------------------------

    @Test
    void restore_neverDeletedContact_returns200AndStaysActive() throws Exception {
        String company = uniqueToken("ZzxNeverDel");
        long id = createContact("Never", "Deleted", uniqueEmail(), company);

        // Restoring an already-active contact is a harmless no-op: 200, deletedAt null.
        mockMvc.perform(post("/api/v1/contacts/{id}/restore", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) id))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());

        // It remains retrievable via the active GET endpoint...
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) id));

        // ...stays in the active list...
        mockMvc.perform(get("/api/v1/contacts").param("search", company))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // ...and was never placed in the trash.
        Assertions.assertThat(trashContainsId(id)).isFalse();
    }

    // ---- (3) GET on a soft-deleted contact does not leak ------------------

    @Test
    void get_softDeletedContact_returns404NoLeak() throws Exception {
        long id = createContact("Soft", "Hidden", uniqueEmail(), uniqueToken("ZzxNoLeak"));

        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        // The soft-deleted contact is treated as not found by the active GET endpoint.
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isNotFound());
    }

    // ---- (4) soft delete excludes from list + search, includes in trash ---

    @Test
    void softDelete_excludedFromListAndSearch_butPresentInTrash() throws Exception {
        // Unique company token => the active list/search matches only this contact.
        String company = uniqueToken("ZzxTrashOnly");
        long id = createContact("List", "Excluded", uniqueEmail(), company);

        // Active before deletion: visible in both the default list and the search.
        mockMvc.perform(get("/api/v1/contacts").param("search", company))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        Assertions.assertThat(activeListContainsId(id)).isTrue();

        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        // Excluded from the search by its unique token (0 hits)...
        mockMvc.perform(get("/api/v1/contacts").param("search", company))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // ...and absent from the (unfiltered) active list as well.
        Assertions.assertThat(activeListContainsId(id)).isFalse();

        // But the trash listing includes it.
        Assertions.assertThat(trashContainsId(id)).isTrue();
    }

    // ---- (5) restore returns to list and removes from trash ---------------

    @Test
    void restore_returnsToListAndRemovesFromTrash() throws Exception {
        String company = uniqueToken("ZzxRestoreEdge");
        long id = createContact("Restore", "Edge", uniqueEmail(), company);

        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        // Sanity: trashed and gone from the active search.
        Assertions.assertThat(trashContainsId(id)).isTrue();
        mockMvc.perform(get("/api/v1/contacts").param("search", company))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // Restore -> 200, deletedAt cleared.
        mockMvc.perform(post("/api/v1/contacts/{id}/restore", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) id))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());

        // Back in the normal list (matched by its unique token)...
        mockMvc.perform(get("/api/v1/contacts").param("search", company))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // ...and removed from the trash.
        Assertions.assertThat(trashContainsId(id)).isFalse();
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
        return pagedContentContainsId("/api/v1/contacts/trash", id);
    }

    /** Returns whether the active (non-trashed) listing currently contains the given id. */
    private boolean activeListContainsId(long id) throws Exception {
        return pagedContentContainsId("/api/v1/contacts", id);
    }

    /** Walks a paginated {@code content} array (size 500) and reports whether the id is present. */
    private boolean pagedContentContainsId(String path, long id) throws Exception {
        MvcResult page = mockMvc.perform(get(path).param("size", "500"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(page.getResponse().getContentAsString())
                .get("content");
        if (content == null) {
            return false;
        }
        for (JsonNode node : content) {
            if (node.get("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }

    /** Extracts the {@code id} from a JSON response body. */
    private long readId(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }
}
