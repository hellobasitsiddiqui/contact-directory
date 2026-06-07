package com.example.contacts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-stack tests for the opt-in optimistic concurrency ({@code @Version}) on
 * the contacts API, exercising controller + service + repository against a real
 * in-memory H2 database (seeded by {@code data.sql}).
 *
 * <p>The "race" between two concurrent editors is simulated <em>deterministically</em>:
 * rather than spawning real threads, a single thread captures a version snapshot
 * {@code V} and then issues two sequential writes that both carry {@code V}. The
 * first write wins (and bumps the stored version); the second is now stale and is
 * rejected with {@code 412 Precondition Failed}. This reproduces exactly the
 * lost-update scenario optimistic locking is designed to prevent, with zero
 * timing flakiness.
 *
 * <p>All request bodies are built as {@link Map}s and serialised with
 * {@link ObjectMapper} so the tests are not coupled to the arity or field order
 * of the {@code ContactRequest} / {@code ContactPatchRequest} records. Each test
 * uses a UUID-based email and is order-independent; no test depends on the
 * seeded rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OptimisticConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Generates an email guaranteed not to collide with seeds or other tests. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /**
     * Builds a full PUT/POST body as a map. A {@code null} version key is omitted
     * entirely so the "absent version" case is genuinely absent, not {@code null}.
     */
    private Map<String, Object> fullBody(String first, String last, String email,
            String company, Long version) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", first);
        body.put("lastName", last);
        body.put("email", email);
        body.put("company", company);
        body.put("favorite", false);
        if (version != null) {
            body.put("version", version);
        }
        return body;
    }

    private long readId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asLong();
    }

    private long readVersion(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("version").asLong();
    }

    /** Creates a contact via the API and returns the full create response node. */
    private JsonNode create(String first, String last, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                fullBody(first, last, email, null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---- (1) PUT with current version -> 200 and version increments -------

    @Test
    void put_withCurrentVersion_returns200AndVersionIncrements() throws Exception {
        String email = uniqueEmail();
        JsonNode created = create("Cur", "Rent", email);
        long id = created.get("id").asLong();
        long version = created.get("version").asLong(); // 0

        // PUT carrying the current version succeeds and bumps the version.
        MvcResult updated = mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                fullBody("Cur", "RentUpdated", email, "Acme", version))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("RentUpdated"))
                .andExpect(jsonPath("$.version").value((int) (version + 1)))
                .andReturn();

        long newVersion = readVersion(updated);

        // A fresh read (new transaction) confirms the incremented version persisted.
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value((int) newVersion));

        // PUT again with the new current version also succeeds and increments once more.
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                fullBody("Cur", "RentAgain", email, "Acme", newVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value((int) (newVersion + 1)));
    }

    // ---- (2) PUT with a stale version -> 412 -----------------------------

    @Test
    void put_withStaleVersion_returns412() throws Exception {
        String email = uniqueEmail();
        JsonNode created = create("Stale", "Put", email);
        long id = created.get("id").asLong();
        long stale = created.get("version").asLong(); // 0 -> becomes stale after the edit below

        // Perform an edit so the stored version moves past `stale`.
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                fullBody("Stale", "PutEdited", email, null, stale))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value((int) (stale + 1)));

        // Re-using the pre-edit version is now stale -> 412.
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                fullBody("Stale", "PutAgain", email, null, stale))))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.status").value(412));

        // The losing write left no trace: lastName is still the winning edit's value.
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("PutEdited"));
    }

    // ---- (3) PATCH with a stale version -> 412 ---------------------------

    @Test
    void patch_withStaleVersion_returns412() throws Exception {
        String email = uniqueEmail();
        JsonNode created = create("Stale", "Patch", email);
        long id = created.get("id").asLong();
        long stale = created.get("version").asLong(); // 0

        // Edit once via PATCH so the stored version advances past `stale`.
        Map<String, Object> firstEdit = new HashMap<>();
        firstEdit.put("company", "FirstCo");
        firstEdit.put("version", stale);
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstEdit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value((int) (stale + 1)));

        // A PATCH still quoting the pre-edit version is rejected.
        Map<String, Object> staleEdit = new HashMap<>();
        staleEdit.put("company", "ShouldNotApply");
        staleEdit.put("version", stale);
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(staleEdit)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.status").value(412));

        // The stale PATCH did not apply.
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company").value("FirstCo"));
    }

    // ---- (4) PUT and PATCH with null/absent version -> 200 (check skipped)

    @Test
    void put_withAbsentVersion_skipsCheckAndReturns200() throws Exception {
        String email = uniqueEmail();
        JsonNode created = create("NoVer", "Put", email);
        long id = created.get("id").asLong();

        // Move the stored version forward so an absent version genuinely "could"
        // have been stale -- proving the check is skipped, not coincidentally passing.
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                fullBody("NoVer", "PutBump", email, null, 0L))))
                .andExpect(status().isOk());

        // No "version" key at all -> concurrency check skipped -> 200.
        Map<String, Object> body = fullBody("NoVer", "PutNoVersion", email, "SkipCo", null);
        org.assertj.core.api.Assertions.assertThat(body).doesNotContainKey("version");
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("PutNoVersion"))
                .andExpect(jsonPath("$.company").value("SkipCo"));
    }

    @Test
    void put_withExplicitNullVersion_skipsCheckAndReturns200() throws Exception {
        String email = uniqueEmail();
        JsonNode created = create("NullVer", "Put", email);
        long id = created.get("id").asLong();

        // Bump the stored version first.
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                fullBody("NullVer", "PutBump", email, null, 0L))))
                .andExpect(status().isOk());

        // Explicit JSON null for "version" -> check skipped -> 200.
        Map<String, Object> body = fullBody("NullVer", "PutExplicitNull", email, null, null);
        body.put("version", null);
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("PutExplicitNull"));
    }

    @Test
    void patch_withAbsentVersion_skipsCheckAndReturns200() throws Exception {
        String email = uniqueEmail();
        JsonNode created = create("NoVer", "Patch", email);
        long id = created.get("id").asLong();

        // Advance the stored version via a versioned PATCH first.
        Map<String, Object> bump = new HashMap<>();
        bump.put("company", "BumpCo");
        bump.put("version", 0L);
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bump)))
                .andExpect(status().isOk());

        // PATCH with no "version" key -> check skipped -> 200.
        Map<String, Object> body = new HashMap<>();
        body.put("company", "SkipPatchCo");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContainKey("version");
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company").value("SkipPatchCo"));
    }

    @Test
    void patch_withExplicitNullVersion_skipsCheckAndReturns200() throws Exception {
        String email = uniqueEmail();
        JsonNode created = create("NullVer", "Patch", email);
        long id = created.get("id").asLong();

        // Bump the stored version first.
        Map<String, Object> bump = new HashMap<>();
        bump.put("company", "BumpCo");
        bump.put("version", 0L);
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bump)))
                .andExpect(status().isOk());

        // Explicit JSON null for "version" -> check skipped -> 200.
        Map<String, Object> body = new HashMap<>();
        body.put("company", "ExplicitNullPatchCo");
        body.put("version", null);
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company").value("ExplicitNullPatchCo"));
    }

    // ---- (5) Deterministic simulated race: two writes with the same V ----

    @Test
    void simulatedRace_secondWriterWithSameCapturedVersion_loses412() throws Exception {
        String email = uniqueEmail();
        JsonNode created = create("Race", "Subject", email);
        long id = created.get("id").asLong();

        // Both "editors" read the contact and capture the same version V.
        MvcResult snapshot = mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andReturn();
        long capturedVersion = readVersion(snapshot); // V (== 0)

        // Editor #1 commits first with V -> wins, 200, version becomes V+1.
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                fullBody("Race", "Editor1Won", email, "Winner", capturedVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Editor1Won"))
                .andExpect(jsonPath("$.version").value((int) (capturedVersion + 1)));

        // Editor #2 commits second, still holding the now-stale V -> loses, 412.
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                fullBody("Race", "Editor2Lost", email, "Loser", capturedVersion))))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.status").value(412));

        // The lost update never landed: editor #1's values stand.
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Editor1Won"))
                .andExpect(jsonPath("$.company").value("Winner"))
                .andExpect(jsonPath("$.version").value((int) (capturedVersion + 1)));
    }

    @Test
    void simulatedRace_crossVerb_putWinsThenPatchWithSameVersionLoses412() throws Exception {
        String email = uniqueEmail();
        JsonNode created = create("Cross", "Race", email);
        long id = created.get("id").asLong();

        long capturedVersion = readVersion(mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andReturn());

        // PUT editor commits first with V.
        mockMvc.perform(put("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                fullBody("Cross", "PutWon", email, "PutCo", capturedVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value((int) (capturedVersion + 1)));

        // PATCH editor, holding the same now-stale V, loses.
        Map<String, Object> patchBody = new HashMap<>();
        patchBody.put("company", "PatchCo");
        patchBody.put("version", capturedVersion);
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchBody)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.status").value(412));

        // PUT editor's write stands.
        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company").value("PutCo"));
    }
}
