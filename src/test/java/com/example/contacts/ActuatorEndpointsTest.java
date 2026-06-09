package com.example.contacts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifies the Spring Boot Actuator surface added for deployment/monitoring:
 *
 * <ul>
 *   <li>{@code /actuator/health} is <em>public</em> (so orchestration liveness /
 *       readiness probes can poll it without a token) and reports {@code UP};</li>
 *   <li>{@code /actuator/metrics} is <em>secured</em> — {@code 401} without a
 *       bearer token, {@code 200} with a valid one.</li>
 * </ul>
 *
 * <p>Like {@link SecurityIntegrationTest}, this drives the real auth endpoint to
 * obtain a token rather than using {@code @WithMockUser}, so the whole filter
 * chain is exercised end-to-end.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorEndpointsTest {

    private static final String HEALTH = "/actuator/health";
    private static final String INFO = "/actuator/info";
    private static final String METRICS = "/actuator/metrics";
    private static final String LOGIN = "/api/v1/auth/login";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Logs in and returns the raw JWT (no "Bearer " prefix). */
    private String tokenFor(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("token").asText();
    }

    @Test
    void health_isPublic_andReportsUp() throws Exception {
        mockMvc.perform(get(HEALTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * {@code show-details: when-authorized} means an anonymous probe sees only the
     * top-level status — no component/disk/db detail. Locks in the documented
     * security boundary.
     */
    @Test
    void health_anonymous_hidesComponentDetail() throws Exception {
        mockMvc.perform(get(HEALTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    /** An authenticated caller sees the component/db/disk detail. */
    @Test
    void health_authenticated_showsComponentDetail() throws Exception {
        String token = tokenFor("admin", "admin123");
        mockMvc.perform(get(HEALTH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").exists());
    }

    /** {@code /actuator/info} is public and returns the configured app metadata. */
    @Test
    void info_isPublic_andReportsAppName() throws Exception {
        mockMvc.perform(get(INFO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.name").value("contacts"));
    }

    @Test
    void metrics_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(METRICS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void metrics_withToken_returns200AndListsMeters() throws Exception {
        String token = tokenFor("admin", "admin123");
        mockMvc.perform(get(METRICS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
    }
}
