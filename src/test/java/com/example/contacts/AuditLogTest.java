package com.example.contacts;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.contacts.service.AuditRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end tests for the append-only audit log. Exercises real JWT auth and
 * the full context so that controller-layer {@code auditService.record(...)}
 * calls actually persist, then reads them back through the admin-only
 * {@code /api/v1/audit} API.
 *
 * <p>Covers: a mutation recording an event (verified via the audit API as an
 * admin), the admin-only access rules ({@code USER} → 403, {@code ADMIN} → 200,
 * unauthenticated → 401) and the {@code actor}/{@code action} filters.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditLogTest {

    private static final String CONTACTS = "/api/v1/contacts";
    private static final String USERS = "/api/v1/users";
    private static final String AUDIT = "/api/v1/audit";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---- helpers ----------------------------------------------------------

    private String body(Map<String, ?> map) throws Exception {
        return objectMapper.writeValueAsString(map);
    }

    private String tokenFromJson(String responseBody) throws Exception {
        return objectMapper.readTree(responseBody).get("token").asText();
    }

    private String adminToken() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        return tokenFromJson(r.getResponse().getContentAsString());
    }

    /** Registers a fresh USER and returns {username, token}. */
    private String[] registerUser() throws Exception {
        String username = "u-" + UUID.randomUUID();
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isCreated())
                .andReturn();
        return new String[]{username, tokenFromJson(r.getResponse().getContentAsString())};
    }

    /** Resolves a user's id by username via the admin-only user list. */
    private long userIdByUsername(String username) throws Exception {
        MvcResult r = mockMvc.perform(get(USERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode node : objectMapper.readTree(r.getResponse().getContentAsString())) {
            if (username.equals(node.get("username").asText())) {
                return node.get("id").asLong();
            }
        }
        throw new AssertionError("User not found in list: " + username);
    }

    private long createContactAs(String token) throws Exception {
        MvcResult r = mockMvc.perform(post(CONTACTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "firstName", "Temp",
                                "lastName", "Contact",
                                "email", "c-" + UUID.randomUUID() + "@example.com"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    // ---- recording --------------------------------------------------------

    @Test
    void mutation_recordsAuditEvent_visibleToAdmin() throws Exception {
        String[] user = registerUser();
        String username = user[0];
        long contactId = createContactAs(user[1]);

        // The most recent CONTACT_CREATE event for this actor should be the one
        // just created (newest-first default sort), carrying the correct fields.
        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", username)
                        .param("action", "CONTACT_CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actor").value(username))
                .andExpect(jsonPath("$.content[0].action").value("CONTACT_CREATE"))
                .andExpect(jsonPath("$.content[0].targetType").value("CONTACT"))
                .andExpect(jsonPath("$.content[0].targetId").value((int) contactId));
    }

    // ---- access control ---------------------------------------------------

    @Test
    void user_cannotReadAuditLog_returns403() throws Exception {
        String userToken = registerUser()[1];
        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_canReadAuditLog_returns200() throws Exception {
        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").exists());
    }

    @Test
    void unauthenticated_cannotReadAuditLog_returns401() throws Exception {
        mockMvc.perform(get(AUDIT)).andExpect(status().isUnauthorized());
    }

    // ---- filtering --------------------------------------------------------

    @Test
    void filterByActor_returnsOnlyThatActorsEvents() throws Exception {
        String[] user = registerUser();
        String username = user[0];
        createContactAs(user[1]);
        // A second, unrelated actor whose events must be excluded by the filter.
        createContactAs(registerUser()[1]);

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.content[*].actor")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(username))));
    }

    @Test
    void filterByAction_returnsOnlyThatAction() throws Exception {
        // Generate at least one CONTACT_CREATE so the filter has a hit.
        createContactAs(registerUser()[1]);

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("action", "CONTACT_CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.content[*].action")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("CONTACT_CREATE"))));
    }

    @Test
    void filterByAction_excludesOtherActions() throws Exception {
        String[] user = registerUser();        // produces AUTH_REGISTER for user[0]
        createContactAs(user[1]);              // produces CONTACT_CREATE for user[0]

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "AUTH_REGISTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].action")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("AUTH_REGISTER"))))
                .andExpect(jsonPath("$.content[*].action")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("CONTACT_CREATE"))));
    }

    @Test
    void filterByActorAndAction_combined() throws Exception {
        String[] user = registerUser();
        long contactId = createContactAs(user[1]);
        // A different actor's CONTACT_CREATE must be excluded by the actor filter.
        createContactAs(registerUser()[1]);

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "CONTACT_CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].actor").value(user[0]))
                .andExpect(jsonPath("$.content[0].action").value("CONTACT_CREATE"))
                .andExpect(jsonPath("$.content[0].targetId").value((int) contactId));
    }

    @Test
    void defaultSort_isTimestampDesc() throws Exception {
        String[] user = registerUser();
        long first = createContactAs(user[1]);
        Thread.sleep(5);
        long second = createContactAs(user[1]);

        // Newest-first default sort: the second (later) create is content[0].
        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "CONTACT_CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].targetId").value((int) second))
                .andExpect(jsonPath("$.content[1].targetId").value((int) first));
    }

    // ---- per-action contact recording ------------------------------------

    @Test
    void updateContact_recordsContactUpdate() throws Exception {
        String[] user = registerUser();
        long id = createContactAs(user[1]);

        mockMvc.perform(put(CONTACTS + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user[1])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "firstName", "New",
                                "lastName", "Name",
                                "email", "n-" + UUID.randomUUID() + "@example.com"))))
                .andExpect(status().isOk());

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "CONTACT_UPDATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].targetId").value((int) id));
    }

    @Test
    void deleteContact_recordsContactDelete() throws Exception {
        String[] user = registerUser();
        long id = createContactAs(user[1]);

        mockMvc.perform(delete(CONTACTS + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user[1]))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "CONTACT_DELETE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("CONTACT_DELETE"))
                .andExpect(jsonPath("$.content[0].targetId").value((int) id));
    }

    @Test
    void restoreContact_recordsContactRestore() throws Exception {
        String[] user = registerUser();
        long id = createContactAs(user[1]);
        mockMvc.perform(delete(CONTACTS + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user[1]))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(CONTACTS + "/" + id + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user[1]))
                .andExpect(status().isOk());

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "CONTACT_RESTORE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].targetId").value((int) id));
    }

    @Test
    void purgeContact_byAdmin_recordsContactPurge() throws Exception {
        // Purge is admin-only; the admin soft-deletes then permanently deletes.
        long id = createContactAs(adminToken());
        mockMvc.perform(delete(CONTACTS + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete(CONTACTS + "/" + id + "/permanent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", "admin")
                        .param("action", "CONTACT_PURGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].targetId").value((int) id));
    }

    @Test
    void bulkDelete_recordsBulkDeleteWithNullTargetIdAndCount() throws Exception {
        String[] user = registerUser();
        long id = createContactAs(user[1]);

        mockMvc.perform(post(CONTACTS + "/bulk/delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user[1])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("ids", List.of(id)))))
                .andExpect(status().isOk());

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "CONTACT_BULK_DELETE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].targetId").doesNotExist())
                .andExpect(jsonPath("$.content[0].summary",
                        org.hamcrest.Matchers.containsString("1")));
    }

    @Test
    void importCsv_recordsContactImportWithSummary() throws Exception {
        String[] user = registerUser();
        String csv = "firstName,lastName,email\nImp,Orted,i-" + UUID.randomUUID() + "@example.com\n";
        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "import.csv", "text/csv", csv.getBytes());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart(CONTACTS + "/import").file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user[1]))
                .andExpect(status().isOk());

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "CONTACT_IMPORT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].targetId").doesNotExist())
                .andExpect(jsonPath("$.content[0].summary",
                        org.hamcrest.Matchers.containsString("imported")));
    }

    @Test
    void uploadAndDeletePhoto_recordPhotoActions() throws Exception {
        String[] user = registerUser();
        long id = createContactAs(user[1]);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        org.springframework.mock.web.MockMultipartFile photo =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "a.png", "image/png", png);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart(CONTACTS + "/" + id + "/photo").file(photo)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user[1]))
                .andExpect(status().isOk());
        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "CONTACT_PHOTO_UPDATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].targetId").value((int) id));

        mockMvc.perform(delete(CONTACTS + "/" + id + "/photo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user[1]))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "CONTACT_PHOTO_DELETE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].targetId").value((int) id));
    }

    // ---- user-management recording ---------------------------------------

    @Test
    void adminRoleChange_recordsUserRoleChange() throws Exception {
        String[] user = registerUser();
        long userId = userIdByUsername(user[0]);

        mockMvc.perform(patch(USERS + "/" + userId + "/role")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("role", "ADMIN"))))
                .andExpect(status().isOk());

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("action", "USER_ROLE_CHANGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actor").value("admin"))
                .andExpect(jsonPath("$.content[0].targetType").value("USER"))
                .andExpect(jsonPath("$.content[0].targetId").value((int) userId));
    }

    @Test
    void adminDeleteUser_recordsUserDelete() throws Exception {
        String[] user = registerUser();
        long userId = userIdByUsername(user[0]);

        mockMvc.perform(delete(USERS + "/" + userId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("action", "USER_DELETE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actor").value("admin"))
                .andExpect(jsonPath("$.content[0].targetType").value("USER"))
                .andExpect(jsonPath("$.content[0].targetId").value((int) userId));
    }

    // ---- auth recording ---------------------------------------------------

    @Test
    void register_recordsAuthRegister() throws Exception {
        String[] user = registerUser();

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "AUTH_REGISTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actor").value(user[0]))
                .andExpect(jsonPath("$.content[0].targetType").value("AUTH"));
    }

    @Test
    void login_recordsAuthLogin() throws Exception {
        // adminToken() logs in as admin, which must be recorded as AUTH_LOGIN.
        String token = adminToken();

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("actor", "admin")
                        .param("action", "AUTH_LOGIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("AUTH_LOGIN"))
                .andExpect(jsonPath("$.content[0].actor").value("admin"))
                .andExpect(jsonPath("$.content[0].targetType").value("AUTH"));
    }

    @Test
    void changePassword_recordsAuthPasswordChange() throws Exception {
        String[] user = registerUser();

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user[1])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "currentPassword", "secret123",
                                "newPassword", "newsecret123"))))
                .andExpect(status().isOk());

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "AUTH_PASSWORD_CHANGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actor").value(user[0]))
                .andExpect(jsonPath("$.content[0].targetType").value("AUTH"));
    }

    // ---- read-only endpoints are not audited ------------------------------

    @Test
    void readOnlyEndpoints_produceNoAuditEvents() throws Exception {
        String[] user = registerUser();
        long id = createContactAs(user[1]);
        // Reuse a single admin token for both counts so the count helper's own
        // login does not record an extra AUTH_LOGIN between the measurements.
        String admin = adminToken();
        long before = auditEventCount(admin);

        // A spread of read-only endpoints: list, get, tags, trash, exports.
        String bearer = "Bearer " + user[1];
        mockMvc.perform(get(CONTACTS).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
        mockMvc.perform(get(CONTACTS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
        mockMvc.perform(get(CONTACTS + "/tags").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
        mockMvc.perform(get(CONTACTS + "/trash").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
        mockMvc.perform(get(CONTACTS + "/export.json").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals(before, auditEventCount(admin),
                "read-only endpoints must not record audit events");
    }

    /** Returns the total number of recorded audit events using the given admin token. */
    private long auditEventCount(String adminToken) throws Exception {
        MvcResult r = mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString())
                .get("totalElements").asLong();
    }

    // ---- field exposure ---------------------------------------------------

    @Test
    void recordedEvent_exposesTimestampAndSummary() throws Exception {
        String[] user = registerUser();
        createContactAs(user[1]);

        mockMvc.perform(get(AUDIT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("actor", user[0])
                        .param("action", "CONTACT_CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].timestamp").exists())
                .andExpect(jsonPath("$.content[0].summary").exists());
    }

    // ---- resilience -------------------------------------------------------

    /**
     * An audit write failure must never break the operation being audited. With
     * {@link AuditRecorder#save} forced to throw on every call, a contact create
     * must still succeed with {@code 201}: the failure is swallowed by
     * {@code AuditService.record} and never surfaces to the caller (and never
     * marks the caller's transaction rollback-only). This lives in a nested
     * context because the {@code @MockBean} replaces the real recorder bean.
     */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class Resilience {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockBean
        private AuditRecorder auditRecorder;

        @Test
        void auditWriteFailure_doesNotBreakMutation() throws Exception {
            doThrow(new RuntimeException("audit db down"))
                    .when(auditRecorder).save(any(), any(), any(), any(), any());

            String username = "u-" + UUID.randomUUID();
            MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("username", username, "password", "secret123"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            String token = objectMapper.readTree(reg.getResponse().getContentAsString())
                    .get("token").asText();

            // Despite every audit write throwing, the contact create still succeeds.
            mockMvc.perform(post(CONTACTS)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "firstName", "Resilient",
                                    "lastName", "Contact",
                                    "email", "r-" + UUID.randomUUID() + "@example.com"))))
                    .andExpect(status().isCreated());
        }
    }
}
