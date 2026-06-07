package com.example.contacts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Verifies role enforcement and the admin user-management API end-to-end with
 * real JWTs: a {@code USER} is forbidden from admin-only actions (permanent
 * delete, user management), an {@code ADMIN} can manage accounts, and the
 * self-protection guards prevent an admin locking themselves out.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RolePermissionsTest {

    private static final String CONTACTS = "/api/v1/contacts";
    private static final String USERS = "/api/v1/users";

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

    /** Registers a fresh USER and returns {username, token, password}. */
    private String[] registerUser(String password) throws Exception {
        String username = "u-" + UUID.randomUUID();
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
        return new String[]{username, tokenFromJson(r.getResponse().getContentAsString()), password};
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

    private long userIdByName(String adminToken, String username) throws Exception {
        MvcResult r = mockMvc.perform(get(USERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode node : objectMapper.readTree(r.getResponse().getContentAsString())) {
            if (username.equals(node.get("username").asText())) {
                return node.get("id").asLong();
            }
        }
        throw new AssertionError("User not found in listing: " + username);
    }

    // ---- contact permission enforcement -----------------------------------

    @Test
    void user_cannotPermanentDeleteContact_returns403() throws Exception {
        String userToken = registerUser("secret123")[1];
        long id = createContactAs(userToken);

        mockMvc.perform(delete(CONTACTS + "/{id}/permanent", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_canPermanentDeleteContact_returns204() throws Exception {
        String admin = adminToken();
        long id = createContactAs(admin);

        mockMvc.perform(delete(CONTACTS + "/{id}/permanent", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isNoContent());
    }

    @Test
    void user_canStillSoftDeleteContact_returns204() throws Exception {
        // Soft delete stays available to ordinary users.
        String userToken = registerUser("secret123")[1];
        long id = createContactAs(userToken);

        mockMvc.perform(delete(CONTACTS + "/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isNoContent());
    }

    // ---- user-management access control ------------------------------------

    @Test
    void user_cannotListUsers_returns403() throws Exception {
        String userToken = registerUser("secret123")[1];
        mockMvc.perform(get(USERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_canListUsers_includesAdmin() throws Exception {
        mockMvc.perform(get(USERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'admin')].role").value(
                        org.hamcrest.Matchers.hasItem("ADMIN")));
    }

    @Test
    void unauthenticated_cannotListUsers_returns401() throws Exception {
        mockMvc.perform(get(USERS)).andExpect(status().isUnauthorized());
    }

    // ---- user-management operations ----------------------------------------

    @Test
    void admin_canPromoteAndDemoteUser() throws Exception {
        String admin = adminToken();
        String username = registerUser("secret123")[0];
        long id = userIdByName(admin, username);

        mockMvc.perform(patch(USERS + "/{id}/role", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("role", "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mockMvc.perform(patch(USERS + "/{id}/role", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("role", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void admin_disableUser_blocksTheirLogin_thenEnableRestores() throws Exception {
        String admin = adminToken();
        String[] user = registerUser("secret123");
        String username = user[0];
        long id = userIdByName(admin, username);

        mockMvc.perform(patch(USERS + "/{id}/enabled", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // Disabled account can no longer log in.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch(USERS + "/{id}/enabled", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        // Re-enabled: login works again.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isOk());
    }

    @Test
    void admin_resetPassword_userLoginsWithNewPassword() throws Exception {
        String admin = adminToken();
        String username = registerUser("oldpass123")[0];
        long id = userIdByName(admin, username);

        mockMvc.perform(post(USERS + "/{id}/reset-password", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("password", "brandnew123"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "oldpass123"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "brandnew123"))))
                .andExpect(status().isOk());
    }

    @Test
    void admin_deleteUser_thenLoginFails() throws Exception {
        String admin = adminToken();
        String username = registerUser("secret123")[0];
        long id = userIdByName(admin, username);

        mockMvc.perform(delete(USERS + "/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isUnauthorized());
    }

    // ---- self-protection guards --------------------------------------------

    @Test
    void admin_cannotDemoteSelf_returns409() throws Exception {
        String admin = adminToken();
        long adminId = userIdByName(admin, "admin");

        mockMvc.perform(patch(USERS + "/{id}/role", adminId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("role", "USER"))))
                .andExpect(status().isConflict());
    }

    @Test
    void admin_cannotDisableSelf_returns409() throws Exception {
        String admin = adminToken();
        long adminId = userIdByName(admin, "admin");

        mockMvc.perform(patch(USERS + "/{id}/enabled", adminId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("enabled", false))))
                .andExpect(status().isConflict());
    }

    @Test
    void admin_cannotDeleteSelf_returns409() throws Exception {
        String admin = adminToken();
        long adminId = userIdByName(admin, "admin");

        mockMvc.perform(delete(USERS + "/{id}", adminId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isConflict());
    }
}
