package com.example.contacts;

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
 * End-to-end tests for the account self-service feature: an authenticated user
 * changing their own password, and the brute-force login lockout.
 *
 * <p>Like {@link SecurityIntegrationTest}, these drive the real authentication
 * endpoints (no {@code @WithMockUser}) so the full filter chain, lockout service
 * and password verification are exercised. Every test registers its own fresh
 * user so the shared {@code admin} account relied on by other suites is never
 * disturbed and never accumulates lock state.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountSelfServiceTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String CHANGE_PASSWORD = "/api/v1/auth/change-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---- helpers ----------------------------------------------------------

    private String json(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    /** Like {@link #json} but for bodies with non-String values (e.g. boolean). */
    private String typedJson(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    /** Looks up a user's id via the admin listing endpoint. */
    private long userIdByName(String adminToken, String username) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode node : objectMapper.readTree(result.getResponse().getContentAsString())) {
            if (username.equals(node.get("username").asText())) {
                return node.get("id").asLong();
            }
        }
        throw new AssertionError("User not found in listing: " + username);
    }

    /** Registers a fresh USER and returns its randomly-generated username. */
    private String registerUser(String username, String password) throws Exception {
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isCreated());
        return username;
    }

    /** Logs in and returns the raw JWT (no "Bearer " prefix). */
    private String tokenFor(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();
    }

    // ---- change password --------------------------------------------------

    @Test
    void changePassword_success_newPasswordWorksAndOldFails() throws Exception {
        String username = "cp-" + UUID.randomUUID();
        String oldPassword = "original123";
        String newPassword = "brandnew456";
        registerUser(username, oldPassword);
        String token = tokenFor(username, oldPassword);

        mockMvc.perform(post(CHANGE_PASSWORD)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "currentPassword", oldPassword,
                                "newPassword", newPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        // The new password now authenticates.
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", newPassword))))
                .andExpect(status().isOk());

        // The old password no longer works.
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", oldPassword))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_wrongCurrentPassword_returns400() throws Exception {
        String username = "cp-" + UUID.randomUUID();
        registerUser(username, "original123");
        String token = tokenFor(username, "original123");

        mockMvc.perform(post(CHANGE_PASSWORD)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "currentPassword", "not-my-password",
                                "newPassword", "brandnew456"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void changePassword_newPasswordTooShort_returns400() throws Exception {
        String username = "cp-" + UUID.randomUUID();
        registerUser(username, "original123");
        String token = tokenFor(username, "original123");

        mockMvc.perform(post(CHANGE_PASSWORD)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "currentPassword", "original123",
                                "newPassword", "123"))))
                .andExpect(status().isBadRequest())
                // It is specifically a bean-validation failure on newPassword
                // (Size min 6), not some other 400.
                .andExpect(jsonPath("$.errors.newPassword").isNotEmpty());
    }

    @Test
    void changePassword_blankCurrentPassword_returns400() throws Exception {
        String username = "cp-" + UUID.randomUUID();
        registerUser(username, "original123");
        String token = tokenFor(username, "original123");

        mockMvc.perform(post(CHANGE_PASSWORD)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "currentPassword", "",
                                "newPassword", "brandnew456"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.currentPassword").isNotEmpty());
    }

    @Test
    void changePassword_missingNewPassword_returns400() throws Exception {
        String username = "cp-" + UUID.randomUUID();
        registerUser(username, "original123");
        String token = tokenFor(username, "original123");

        // newPassword omitted entirely -> @NotBlank fails.
        mockMvc.perform(post(CHANGE_PASSWORD)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", "original123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").isNotEmpty());
    }

    @Test
    void changePassword_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(CHANGE_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "currentPassword", "original123",
                                "newPassword", "brandnew456"))))
                .andExpect(status().isUnauthorized());
    }

    // ---- brute-force lockout ----------------------------------------------

    /**
     * After {@code max-login-attempts} (5 in the test profile) consecutive
     * failures the account is locked, and the very next attempt — even with the
     * correct password — is rejected with {@code 423 Locked}.
     */
    @Test
    void lockout_afterMaxFailedAttempts_correctPasswordIsRejectedWith423() throws Exception {
        String username = "lock-" + UUID.randomUUID();
        String password = "correct123";
        registerUser(username, password);

        // Five consecutive wrong-password attempts trip the lock.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post(LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", username, "password", "wrong-password"))))
                    .andExpect(status().isUnauthorized());
        }

        // The next attempt is locked out even though the password is correct.
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.status").value(423));
    }

    /**
     * A successful login before the threshold is reached resets the failure
     * counter, so the account is not locked afterwards even with more failures
     * than would otherwise be tolerated across the whole session.
     */
    @Test
    void lockout_successfulLoginResetsFailureCounter() throws Exception {
        String username = "lock-" + UUID.randomUUID();
        String password = "correct123";
        registerUser(username, password);

        // Four failures (one short of the limit), then a successful login resets.
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post(LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", username, "password", "wrong-password"))))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk());

        // Counter is back to zero: four more failures still do not lock, and the
        // correct password continues to authenticate (not 423).
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post(LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", username, "password", "wrong-password"))))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk());
    }

    /**
     * An unknown username can be hammered well past the threshold without ever
     * creating lock state: every attempt returns {@code 401}, never {@code 423}.
     * Protects the contract guarantee that lockout never leaks account existence.
     */
    @Test
    void lockout_unknownUsername_neverLocksAlways401() throws Exception {
        String username = "ghost-" + UUID.randomUUID(); // never registered

        for (int i = 0; i < 7; i++) { // well past max-login-attempts (5)
            mockMvc.perform(post(LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", username, "password", "whatever"))))
                    .andExpect(status().isUnauthorized()); // always 401, never 423
        }
    }

    /**
     * Pins the lockout boundary: the {@code max-login-attempts}-th wrong attempt
     * (the 5th) still returns {@code 401} because {@code assertNotLocked} runs at
     * the start of the request; the lock only bites on the following request,
     * which is {@code 423} even though the password is still wrong.
     */
    @Test
    void lockout_fifthWrongAttemptStill401_sixthIs423() throws Exception {
        String username = "bound-" + UUID.randomUUID();
        String password = "correct123";
        registerUser(username, password);

        for (int i = 0; i < 5; i++) { // attempts 1..5 all still 401
            mockMvc.perform(post(LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", username, "password", "wrong-password"))))
                    .andExpect(status().isUnauthorized());
        }

        // 6th request is rejected before auth even with a WRONG password, proving
        // the lock (not credential validity) drives the 423.
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", "still-wrong"))))
                .andExpect(status().isLocked());
    }

    /**
     * A disabled account is rejected with {@code 401} via {@code DisabledException},
     * which the login flow does not catch, so the lockout machinery never runs:
     * repeated logins stay {@code 401} and never escalate to {@code 423}. Guards
     * the contract requirement that lockout must not break disabled-account handling.
     */
    @Test
    void disabledAccount_repeatedLogins_stay401_notLockedOut() throws Exception {
        String username = "dis-" + UUID.randomUUID();
        registerUser(username, "secret123");

        String adminToken = tokenFor("admin", "admin123");
        long id = userIdByName(adminToken, username);
        mockMvc.perform(patch("/api/v1/users/" + id + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(typedJson(Map.of("enabled", false))))
                .andExpect(status().isOk());

        // Several correct-password logins on a disabled account: always 401, never 423.
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post(LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", username, "password", "secret123"))))
                    .andExpect(status().isUnauthorized());
        }
    }

    /**
     * Sanity check that the {@code /me} endpoint exposes {@code createdAt}
     * (added for the profile page) alongside the existing username and role,
     * so existing consumers keep working.
     */
    @Test
    void me_returnsCreatedAtAlongsideUsernameAndRole() throws Exception {
        String username = "me-" + UUID.randomUUID();
        registerUser(username, "secret123");
        String token = tokenFor(username, "secret123");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }
}
