package com.example.contacts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * End-to-end tests for the JWT security layer: unauthenticated access is
 * rejected with {@code 401}, login/register issue working bearer tokens, bad
 * credentials and duplicate usernames are handled, and tokens unlock the
 * otherwise-protected contacts API.
 *
 * <p>Unlike the other full-stack tests, this class does <em>not</em> use
 * {@code @WithMockUser} — it drives the real authentication endpoints so the
 * filter chain, token issuance and validation are all exercised. The default
 * admin account (admin/admin123) is seeded by {@code DataInitializer} on
 * startup.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    private static final String CONTACTS = "/api/v1/contacts";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REGISTER = "/api/v1/auth/register";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String json(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    /** Logs in and returns the raw JWT (no "Bearer " prefix). */
    private String tokenFor(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("token").asText();
    }

    @Test
    void protectedEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(CONTACTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedEndpoint_withGarbageToken_returns401() throws Exception {
        mockMvc.perform(get(CONTACTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_withDefaultAdmin_returnsTokenAndRole() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void login_thenAccessProtectedEndpoint_returns200() throws Exception {
        String token = tokenFor("admin", "admin123");
        mockMvc.perform(get(CONTACTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void login_withWrongPassword_returns401() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "admin", "password", "wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_withUnknownUser_returns401() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "no-such-user", "password", "whatever"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_newUser_returns201AndWorkingToken() throws Exception {
        String username = "user-" + UUID.randomUUID();

        MvcResult result = mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();

        // The freshly-issued token unlocks the protected API.
        mockMvc.perform(get(CONTACTS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void register_duplicateUsername_returns409() throws Exception {
        String username = "dup-" + UUID.randomUUID();
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", "another123"))))
                .andExpect(status().isConflict());
    }

    @Test
    void register_shortPassword_returns400() throws Exception {
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "shorty-" + UUID.randomUUID(), "password", "123"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void me_withToken_returnsCurrentUser() throws Exception {
        String token = tokenFor("admin", "admin123");
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void me_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // ---- HSTS (CD-027) ----------------------------------------------------

    /**
     * Over HTTPS the app must advertise HSTS so browsers pin the site to TLS.
     * (Behind a TLS-terminating proxy the request is "secure" via the forwarded
     * proto; {@code .secure(true)} simulates that here.)
     */
    @Test
    void secureRequest_emitsHstsHeader() throws Exception {
        mockMvc.perform(get("/").secure(true))
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("max-age=31536000"),
                                org.hamcrest.Matchers.containsString("includeSubDomains"),
                                org.hamcrest.Matchers.containsString("preload"))));
    }

    /**
     * Over plain HTTP the HSTS header must NOT be sent — it would be meaningless
     * (browsers ignore it on insecure responses) and signals nothing useful for
     * local/dev HTTP. Spring's default {@code SecureRequestMatcher} guarantees this.
     */
    @Test
    void insecureRequest_doesNotEmitHstsHeader() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
}
