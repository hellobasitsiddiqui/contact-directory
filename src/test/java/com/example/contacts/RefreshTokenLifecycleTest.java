package com.example.contacts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end tests for the refresh-token lifecycle (CD-028): issuance on
 * login/register, rotation, reuse (theft) detection, logout revocation, and
 * the lifecycle events that revoke sessions (change/reset password, disable,
 * delete). Time is driven by a {@link MutableClock} so expiry and the reuse
 * grace window are crossed deterministically, without sleeping.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefreshTokenLifecycleTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REGISTER = "/api/v1/auth/register";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String CHANGE_PASSWORD = "/api/v1/auth/change-password";
    private static final String CONTACTS = "/api/v1/contacts";
    private static final String USERS = "/api/v1/users";

    /** Manually advanced clock injected (as @Primary) into RefreshTokenService. */
    static final MutableClock CLOCK = new MutableClock();

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    /** A Clock whose instant is advanced explicitly by tests. */
    static final class MutableClock extends Clock {
        private volatile Instant current = Instant.now();

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetClockDrift() {
        // Keep the clock roughly at real time so JWT issuance (which uses the
        // system clock) and refresh rows (test clock) stay coherent per test.
        CLOCK.current = Instant.now();
    }

    private String json(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    /** Registers a fresh USER and returns the full auth body (token pair). */
    private JsonNode registerFresh() throws Exception {
        String username = "rt-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode loginAs(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post(REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refreshToken))))
                .andReturn();
    }

    private JsonNode refreshOk(String refreshToken) throws Exception {
        MvcResult result = refresh(refreshToken);
        org.junit.jupiter.api.Assertions.assertEquals(200, result.getResponse().getStatus(),
                "expected refresh to succeed");
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void expectRefreshUnauthorized(String refreshToken) throws Exception {
        mockMvc.perform(post(REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid refresh token"));
    }

    // ---- issuance -----------------------------------------------------------

    @Test
    void loginAndRegister_returnTokenPairWithLifetimes() throws Exception {
        JsonNode registered = registerFresh();
        org.assertj.core.api.Assertions.assertThat(registered.get("refreshToken").asText()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(registered.get("refreshExpiresInMs").asLong()).isPositive();

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshExpiresInMs").isNumber());
    }

    // ---- rotation & reuse ---------------------------------------------------

    @Test
    void refresh_rotates_andNewPairWorks() throws Exception {
        JsonNode pair = registerFresh();
        String original = pair.get("refreshToken").asText();

        JsonNode rotated = refreshOk(original);
        String newAccess = rotated.get("token").asText();
        String newRefresh = rotated.get("refreshToken").asText();
        org.assertj.core.api.Assertions.assertThat(newRefresh).isNotEqualTo(original);

        // The new access token authenticates API calls.
        mockMvc.perform(get(CONTACTS).header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess))
                .andExpect(status().isOk());

        // The rotated-to token itself refreshes fine later.
        refreshOk(newRefresh);
    }

    @Test
    void reusedToken_withinGrace_isBenignSibling() throws Exception {
        JsonNode pair = registerFresh();
        String original = pair.get("refreshToken").asText();

        refreshOk(original);
        // Same token again, immediately (well inside the 30s grace window):
        // treated as a concurrent-tab retry, NOT theft — a sibling is minted.
        JsonNode sibling = refreshOk(original);
        org.assertj.core.api.Assertions.assertThat(sibling.get("refreshToken").asText())
                .isNotEqualTo(original);
    }

    @Test
    void reusedToken_beyondGrace_revokesWholeFamily() throws Exception {
        JsonNode pair = registerFresh();
        String original = pair.get("refreshToken").asText();

        JsonNode rotated = refreshOk(original);
        String successor = rotated.get("refreshToken").asText();

        // Past the grace window the replay is treated as theft...
        CLOCK.advance(Duration.ofSeconds(31));
        expectRefreshUnauthorized(original);
        // ...and the WHOLE family is dead, including the legitimate successor.
        expectRefreshUnauthorized(successor);
    }

    @Test
    void expiredRefreshToken_isRejected() throws Exception {
        JsonNode pair = registerFresh();
        String refreshToken = pair.get("refreshToken").asText();

        CLOCK.advance(Duration.ofDays(15)); // past the 14d lifetime
        expectRefreshUnauthorized(refreshToken);
    }

    @Test
    void refresh_validation_andTokenConfusion() throws Exception {
        // Blank token -> bean validation 400.
        mockMvc.perform(post(REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", ""))))
                .andExpect(status().isBadRequest());

        // An ACCESS JWT presented as a refresh token -> generic 401.
        JsonNode pair = registerFresh();
        expectRefreshUnauthorized(pair.get("token").asText());

        // A refresh secret presented as a BEARER token -> 401 on protected APIs.
        mockMvc.perform(get(CONTACTS).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + pair.get("refreshToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    // ---- logout -------------------------------------------------------------

    @Test
    void logout_revokesSession_andIsIdempotent() throws Exception {
        JsonNode pair = registerFresh();
        String refreshToken = pair.get("refreshToken").asText();

        mockMvc.perform(post(LOGOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isNoContent());

        expectRefreshUnauthorized(refreshToken);

        // Idempotent: repeating, sending unknown tokens or no body still 204s.
        mockMvc.perform(post(LOGOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(LOGOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", "no-such-token"))))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(LOGOUT))
                .andExpect(status().isNoContent());
    }

    @Test
    void logout_revokesTheWholeRotationFamily() throws Exception {
        JsonNode pair = registerFresh();
        String original = pair.get("refreshToken").asText();
        String successor = refreshOk(original).get("refreshToken").asText();

        // Logging out with the LATEST token kills the lineage root too.
        mockMvc.perform(post(LOGOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", successor))))
                .andExpect(status().isNoContent());

        expectRefreshUnauthorized(successor);
        CLOCK.advance(Duration.ofSeconds(31));
        expectRefreshUnauthorized(original);
    }

    // ---- lifecycle revocation -----------------------------------------------

    @Test
    void changePassword_revokesOtherSessions_andReturnsFreshPair() throws Exception {
        String username = "rt-cp-" + UUID.randomUUID();
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isCreated());

        JsonNode deviceA = loginAs(username, "secret123");
        JsonNode deviceB = loginAs(username, "secret123");

        MvcResult result = mockMvc.perform(post(CHANGE_PASSWORD)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + deviceA.get("token").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "currentPassword", "secret123",
                                "newPassword", "brandnew456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully"))
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.username").value(username))
                .andReturn();

        // Every pre-change refresh session is dead, on every device...
        expectRefreshUnauthorized(deviceA.get("refreshToken").asText());
        expectRefreshUnauthorized(deviceB.get("refreshToken").asText());

        // ...but the fresh pair handed back keeps THIS session alive.
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        refreshOk(body.get("refreshToken").asText());
    }

    @Test
    void adminLifecycleActions_revokeRefreshSessions() throws Exception {
        String adminToken = loginAs("admin", "admin123").get("token").asText();

        // -- reset password revokes the target's sessions
        JsonNode pairReset = registerFresh();
        Long idReset = userIdOf(adminToken, pairReset.get("username").asText());
        mockMvc.perform(post(USERS + "/" + idReset + "/reset-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", "resetpass789"))))
                .andExpect(status().isOk());
        expectRefreshUnauthorized(pairReset.get("refreshToken").asText());

        // -- disabling revokes the target's sessions
        JsonNode pairDisable = registerFresh();
        Long idDisable = userIdOf(adminToken, pairDisable.get("username").asText());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(USERS + "/" + idDisable + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk());
        expectRefreshUnauthorized(pairDisable.get("refreshToken").asText());

        // -- deleting kills refresh AND the still-valid access token gets a
        //    clean 401 (regression for the deleted-user 500 in the JWT filter)
        JsonNode pairDelete = registerFresh();
        Long idDelete = userIdOf(adminToken, pairDelete.get("username").asText());
        mockMvc.perform(delete(USERS + "/" + idDelete)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        expectRefreshUnauthorized(pairDelete.get("refreshToken").asText());
        mockMvc.perform(get(CONTACTS).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + pairDelete.get("token").asText()))
                .andExpect(status().isUnauthorized());
    }

    /** Looks up a user's id via the admin users list. */
    private Long userIdOf(String adminToken, String username) throws Exception {
        MvcResult result = mockMvc.perform(get(USERS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode users = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode user : users) {
            if (username.equals(user.get("username").asText())) {
                return user.get("id").asLong();
            }
        }
        throw new AssertionError("User not found in admin list: " + username);
    }
}
