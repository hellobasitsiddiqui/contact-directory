package com.example.contacts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end tests for per-user contact ownership, driven with real JWTs so the
 * security filter resolves a genuinely distinct owner for each request.
 *
 * <p>Two ordinary users (A and B) are registered fresh per test, so each gets a
 * distinct id. The assertions confirm that a non-admin can only see and act on
 * their own contacts — every cross-owner access returns {@code 404} so the
 * existence of another user's contact is never revealed — while an administrator
 * operates unscoped across all owners. The final case proves email uniqueness is
 * per-owner: two users may each hold the same email without a {@code 409}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrossUserIsolationTest {

    private static final String CONTACTS = "/api/v1/contacts";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---- helpers ----------------------------------------------------------

    private String json(Map<String, ?> map) throws Exception {
        return objectMapper.writeValueAsString(map);
    }

    private String tokenFromJson(String responseBody) throws Exception {
        return objectMapper.readTree(responseBody).get("token").asText();
    }

    /** Registers a fresh USER and returns their bearer token. */
    private String registerUserToken() throws Exception {
        String username = "iso-" + UUID.randomUUID();
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isCreated())
                .andReturn();
        return tokenFromJson(r.getResponse().getContentAsString());
    }

    /** Logs in as the seeded default admin and returns their bearer token. */
    private String adminToken() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        return tokenFromJson(r.getResponse().getContentAsString());
    }

    /** Creates a contact as the given token holder and returns its generated id. */
    private long createContactAs(String token, String first, String last, String email)
            throws Exception {
        MvcResult r = mockMvc.perform(post(CONTACTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "firstName", first,
                                "lastName", last,
                                "email", email))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    /** Whether the active list for the given token contains a contact with the id. */
    private boolean listContainsId(String token, long id) throws Exception {
        MvcResult r = mockMvc.perform(get(CONTACTS).param("size", "500")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(r.getResponse().getContentAsString()).get("content");
        if (content != null) {
            for (JsonNode node : content) {
                if (node.get("id").asLong() == id) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- cross-user read/mutate isolation ---------------------------------

    @Test
    void user_cannotGetAnotherUsersContact_returns404() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        long bContact = createContactAs(userB, "Bob", "Owner",
                "b-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(get(CONTACTS + "/{id}", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA))
                .andExpect(status().isNotFound());
    }

    @Test
    void user_cannotUpdateAnotherUsersContact_returns404() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        long bContact = createContactAs(userB, "Bob", "Owner",
                "b-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(put(CONTACTS + "/{id}", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "firstName", "Hacked",
                                "lastName", "Owner",
                                "email", "x-" + UUID.randomUUID() + "@example.com"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void user_cannotPatchAnotherUsersContact_returns404() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        long bContact = createContactAs(userB, "Bob", "Owner",
                "b-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(patch(CONTACTS + "/{id}", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("company", "Hijacked Inc"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void user_cannotDeleteAnotherUsersContact_returns404() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        long bContact = createContactAs(userB, "Bob", "Owner",
                "b-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(delete(CONTACTS + "/{id}", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA))
                .andExpect(status().isNotFound());

        // B's contact is untouched and still visible to B.
        mockMvc.perform(get(CONTACTS + "/{id}", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userB))
                .andExpect(status().isOk());
    }

    @Test
    void user_doesNotSeeAnotherUsersContactsInList() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        long aContact = createContactAs(userA, "Alice", "Owner",
                "a-" + UUID.randomUUID() + "@example.com");
        long bContact = createContactAs(userB, "Bob", "Owner",
                "b-" + UUID.randomUUID() + "@example.com");

        // A sees only A's contact, never B's.
        org.assertj.core.api.Assertions.assertThat(listContainsId(userA, aContact)).isTrue();
        org.assertj.core.api.Assertions.assertThat(listContainsId(userA, bContact)).isFalse();

        // ...and symmetrically for B.
        org.assertj.core.api.Assertions.assertThat(listContainsId(userB, bContact)).isTrue();
        org.assertj.core.api.Assertions.assertThat(listContainsId(userB, aContact)).isFalse();
    }

    // ---- admin operates unscoped ------------------------------------------

    @Test
    void admin_seesContactsAcrossOwners() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        long aContact = createContactAs(userA, "Alice", "Admin-visible",
                "a-" + UUID.randomUUID() + "@example.com");
        long bContact = createContactAs(userB, "Bob", "Admin-visible",
                "b-" + UUID.randomUUID() + "@example.com");

        String admin = adminToken();

        // The admin can fetch either user's contact directly...
        mockMvc.perform(get(CONTACTS + "/{id}", aContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Alice"));
        mockMvc.perform(get(CONTACTS + "/{id}", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Bob"));

        // ...and both appear in the admin's unscoped list.
        org.assertj.core.api.Assertions.assertThat(listContainsId(admin, aContact)).isTrue();
        org.assertj.core.api.Assertions.assertThat(listContainsId(admin, bContact)).isTrue();
    }

    // ---- per-owner email uniqueness ---------------------------------------

    @Test
    void twoUsersCanEachCreateContactWithSameEmail_noConflict() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        String sharedEmail = "shared-" + UUID.randomUUID() + "@example.com";

        // Both creations succeed: uniqueness is scoped per owner, not global.
        long aContact = createContactAs(userA, "Alice", "Shared", sharedEmail);
        long bContact = createContactAs(userB, "Bob", "Shared", sharedEmail);

        org.assertj.core.api.Assertions.assertThat(aContact).isNotEqualTo(bContact);
    }

    @Test
    void sameUserCannotCreateTwoContactsWithSameEmail_returns409() throws Exception {
        String userA = registerUserToken();
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        createContactAs(userA, "Alice", "First", email);

        // The same owner reusing the email (case-insensitively) is rejected.
        mockMvc.perform(post(CONTACTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "firstName", "Alice",
                                "lastName", "Second",
                                "email", email.toUpperCase()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // ---- cross-user photo isolation ---------------------------------------

    @Test
    void user_cannotAccessAnotherUsersPhoto_returns404() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        long bContact = createContactAs(userB, "Bob", "Owner", "b-" + UUID.randomUUID() + "@example.com");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        // B uploads a photo to their own contact.
        mockMvc.perform(multipart(CONTACTS + "/{id}/photo", bContact)
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "b.png", "image/png", png))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userB))
                .andExpect(status().isOk());
        // A cannot read it...
        mockMvc.perform(get(CONTACTS + "/{id}/photo", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA))
                .andExpect(status().isNotFound());
        // ...nor overwrite it...
        mockMvc.perform(multipart(CONTACTS + "/{id}/photo", bContact)
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "a.png", "image/png", png))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA))
                .andExpect(status().isNotFound());
        // ...nor delete it.
        mockMvc.perform(delete(CONTACTS + "/{id}/photo", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA))
                .andExpect(status().isNotFound());
    }

    // ---- cross-user trash/restore isolation -------------------------------

    @Test
    void user_cannotRestoreOrSeeAnotherUsersTrashedContact_returns404() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        long bContact = createContactAs(userB, "Bob", "Trashed", "b-" + UUID.randomUUID() + "@example.com");
        // B soft-deletes their own contact.
        mockMvc.perform(delete(CONTACTS + "/{id}", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userB))
                .andExpect(status().isNoContent());
        // A cannot restore B's trashed contact.
        mockMvc.perform(post(CONTACTS + "/{id}/restore", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA))
                .andExpect(status().isNotFound());
        // B's trashed contact is invisible in A's trash list.
        MvcResult r = mockMvc.perform(get(CONTACTS + "/trash").param("size", "500")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA))
                .andExpect(status().isOk()).andReturn();
        JsonNode content = objectMapper.readTree(r.getResponse().getContentAsString()).get("content");
        if (content != null) {
            for (JsonNode n : content) {
                org.assertj.core.api.Assertions.assertThat(n.get("id").asLong()).isNotEqualTo(bContact);
            }
        }
    }

    // ---- cross-user bulk isolation ----------------------------------------

    @Test
    void bulkDelete_doesNotTouchAnotherUsersContact() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        long aContact = createContactAs(userA, "Alice", "Mine", "a-" + UUID.randomUUID() + "@example.com");
        long bContact = createContactAs(userB, "Bob", "Theirs", "b-" + UUID.randomUUID() + "@example.com");
        // A bulk-deletes both their own and B's id.
        mockMvc.perform(post(CONTACTS + "/bulk/delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("ids", java.util.List.of(aContact, bContact)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(1)); // only A's own counts
        // B's contact is untouched and still active/visible to B.
        mockMvc.perform(get(CONTACTS + "/{id}", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userB))
                .andExpect(status().isOk());
    }

    // ---- cross-user export/import isolation --------------------------------

    @Test
    void exportJson_excludesAnotherUsersContacts() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        String aEmail = "a-" + UUID.randomUUID() + "@example.com";
        String bEmail = "b-" + UUID.randomUUID() + "@example.com";
        createContactAs(userA, "Alice", "Export", aEmail);
        createContactAs(userB, "Bob", "Export", bEmail);
        MvcResult r = mockMvc.perform(get(CONTACTS + "/export.json")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains(aEmail);
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain(bEmail);
    }

    @Test
    void import_dedupIsPerOwner_userCanImportEmailAnotherOwnerAlreadyHas() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        String shared = "shared-" + UUID.randomUUID() + "@example.com";
        createContactAs(userB, "Bob", "Holder", shared); // B already holds the email
        String csv = "firstName,lastName,email\nAlice,Imported," + shared + "\n";
        mockMvc.perform(multipart(CONTACTS + "/import")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "c.csv", "text/csv", csv.getBytes()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))   // not skipped: dedup is per-owner
                .andExpect(jsonPath("$.skipped").value(0));
    }

    // ---- cross-user search/tag isolation ----------------------------------

    @Test
    void search_andTags_areScopedToOwner() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        // B owns a contact with a distinctive name + tag.
        long bContact = createContactAs(userB, "Zelphine", "Uniqua", "b-" + UUID.randomUUID() + "@example.com");
        mockMvc.perform(patch(CONTACTS + "/{id}", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tags", java.util.List.of("ZZTopSecret")))))
                .andExpect(status().isOk());
        // A's search by B's distinctive term returns nothing.
        mockMvc.perform(get(CONTACTS).param("search", "Zelphine").param("size", "500")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        // A's tag filter by B's tag returns nothing.
        mockMvc.perform(get(CONTACTS).param("tag", "ZZTopSecret").param("size", "500")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        // A's tag list does not contain B's tag.
        MvcResult r = mockMvc.perform(get(CONTACTS + "/tags")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userA))
                .andExpect(status().isOk()).andReturn();
        org.assertj.core.api.Assertions.assertThat(r.getResponse().getContentAsString()).doesNotContain("ZZTopSecret");
    }

    // ---- admin operates unscoped for writes -------------------------------

    @Test
    void admin_canUpdateAndDeleteAnotherUsersContact() throws Exception {
        String userB = registerUserToken();
        long bContact = createContactAs(userB, "Bob", "Owned", "b-" + UUID.randomUUID() + "@example.com");
        String admin = adminToken();
        // Admin patches B's contact unscoped.
        mockMvc.perform(patch(CONTACTS + "/{id}", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("company", "AdminEdited"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company").value("AdminEdited"));
        // Admin soft-deletes B's contact unscoped.
        mockMvc.perform(delete(CONTACTS + "/{id}", bContact)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isNoContent());
    }
}
