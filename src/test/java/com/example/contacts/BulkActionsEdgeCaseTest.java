package com.example.contacts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.contacts.dto.ContactRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-stack edge-case coverage for the bulk action endpoints
 * ({@code /bulk/delete}, {@code /bulk/favorite}, {@code /bulk/tags}) against a
 * real in-memory H2 database.
 *
 * <p>Every test creates its own contacts with unique emails and operates only on
 * those ids, so {@code affected} counts are deterministic and exact even though
 * the seeded rows and other tests share the database. No test depends on the
 * ordering of rows or on the seeded data.
 *
 * <p>An id well outside any generated sequence ({@code 9_999_999L}) stands in for
 * a guaranteed-missing contact.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class BulkActionsEdgeCaseTest {

    /** An id far beyond anything the sequence will generate during a test run. */
    private static final long MISSING_ID = 9_999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Generates an email guaranteed not to collide with seeds or other tests. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    // ---- (1) bulk/delete with empty ids -> affected 0 --------------------

    @Test
    void bulkDelete_emptyIds_returnsZeroAffected() throws Exception {
        mockMvc.perform(post("/api/v1/contacts/bulk/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(0));
    }

    // ---- (2) bulk/delete with only non-existent ids -> affected 0 --------

    @Test
    void bulkDelete_onlyMissingIds_returnsZeroAffected() throws Exception {
        String body = "{\"ids\":[" + MISSING_ID + "," + (MISSING_ID + 1) + "," + (MISSING_ID + 2) + "]}";
        mockMvc.perform(post("/api/v1/contacts/bulk/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(0));
    }

    // ---- (3) bulk/favorite over a MIX: valid-active + missing + trashed ---

    @Test
    void bulkFavorite_mixOfActiveMissingAndTrashed_countsOnlyActiveAndFlipsFlag() throws Exception {
        // Two active contacts, both starting non-favourite.
        long activeOne = createContact("Mix", "FavActiveOne", uniqueEmail());
        long activeTwo = createContact("Mix", "FavActiveTwo", uniqueEmail());
        // One contact that is soft-deleted before the bulk call (must be skipped).
        long trashed = createContact("Mix", "FavTrashed", uniqueEmail());
        mockMvc.perform(delete("/api/v1/contacts/{id}", trashed))
                .andExpect(status().isNoContent());

        // Sanity: the two active ones are not favourites yet.
        assertFavorite(activeOne, false);
        assertFavorite(activeTwo, false);

        // ids: two active + one missing + one trashed -> only the two active count.
        String body = "{\"ids\":[" + activeOne + "," + MISSING_ID + "," + trashed + "," + activeTwo
                + "],\"favorite\":true}";
        mockMvc.perform(post("/api/v1/contacts/bulk/favorite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(2));

        // The favourite flag actually flipped on the two active contacts...
        assertFavorite(activeOne, true);
        assertFavorite(activeTwo, true);

        // ...and the trashed contact remains in the trash (untouched, still 404 active).
        mockMvc.perform(get("/api/v1/contacts/{id}", trashed))
                .andExpect(status().isNotFound());
    }

    // ---- (4) bulk/tags addTags + removeTags over several ids -------------

    @Test
    void bulkTags_addAndRemoveAcrossSeveralContacts_countsCorrectlyAndTagsChange() throws Exception {
        String keep = "ZzxKeep" + UUID.randomUUID().toString().substring(0, 6);
        String add = "ZzxAdd" + UUID.randomUUID().toString().substring(0, 6);
        String drop = "ZzxDrop" + UUID.randomUUID().toString().substring(0, 6);

        // Three active contacts, each starting with the 'keep' and 'drop' tags.
        long one = createContactWithTags("Tags", "One", uniqueEmail(), Set.of(keep, drop));
        long two = createContactWithTags("Tags", "Two", uniqueEmail(), Set.of(keep, drop));
        long three = createContactWithTags("Tags", "Three", uniqueEmail(), Set.of(keep, drop));

        String body = "{\"ids\":[" + one + "," + two + "," + three + "],"
                + "\"addTags\":[\"" + add + "\"],\"removeTags\":[\"" + drop + "\"]}";
        mockMvc.perform(post("/api/v1/contacts/bulk/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(3));

        // Every contact gained 'add', lost 'drop', and kept 'keep'.
        for (long id : new long[] {one, two, three}) {
            mockMvc.perform(get("/api/v1/contacts/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tags", Matchers.hasItem(add)))
                    .andExpect(jsonPath("$.tags", Matchers.hasItem(keep)))
                    .andExpect(jsonPath("$.tags", Matchers.not(Matchers.hasItem(drop))));
        }
    }

    // ---- (5) duplicate ids in the list are not double-counted ------------

    @Test
    void bulkFavorite_duplicateIds_areNotDoubleCounted() throws Exception {
        long a = createContact("Dup", "FavA", uniqueEmail());
        long b = createContact("Dup", "FavB", uniqueEmail());

        // a appears three times, b twice -> two distinct active contacts -> affected 2.
        String body = "{\"ids\":[" + a + "," + a + "," + b + "," + a + "," + b
                + "],\"favorite\":true}";
        mockMvc.perform(post("/api/v1/contacts/bulk/favorite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(2));

        assertFavorite(a, true);
        assertFavorite(b, true);
    }

    @Test
    void bulkDelete_duplicateIds_areNotDoubleCounted() throws Exception {
        long id = createContact("Dup", "Del", uniqueEmail());

        // The same active id listed three times -> soft-deleted once -> affected 1.
        String body = "{\"ids\":[" + id + "," + id + "," + id + "]}";
        mockMvc.perform(post("/api/v1/contacts/bulk/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(1));

        // It is now soft-deleted (no longer retrievable as active).
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isNotFound());
    }

    // ---- Helpers ----------------------------------------------------------

    /** Creates a contact via the API and returns its generated id. */
    private long createContact(String first, String last, String email) throws Exception {
        return createContactWithTags(first, last, email, null);
    }

    /** Creates a contact (optionally with tags) via the API and returns its id. */
    private long createContactWithTags(String first, String last, String email, Set<String> tags)
            throws Exception {
        ContactRequest req = new ContactRequest(first, last, email,
                null, null, tags, false, null, null);
        MvcResult result = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    /** Asserts the active contact's favourite flag has the expected value. */
    private void assertFavorite(long id, boolean expected) throws Exception {
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(expected));
    }
}
