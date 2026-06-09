package com.example.contacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level end-to-end tests that boot the real server on a random port and
 * exercise it over genuine HTTP via {@link TestRestTemplate} — unlike the rest
 * of the suite, which drives the dispatcher through {@code MockMvc}.
 *
 * <p>This means the embedded servlet container, the full security filter chain,
 * real JSON (de)serialisation over the wire and actual HTTP status codes are all
 * in play, giving a closer-to-production smoke of the documented happy paths:
 * auth → contact lifecycle → cross-user isolation/403 → admin users/audit →
 * actuator health.
 *
 * <p>The seeded admin account (admin/admin123) comes from {@code DataInitializer}
 * and the in-memory test database; ordinary users are registered fresh per test
 * with unique names so the cases stay independent and order-agnostic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpEndToEndTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The default {@code TestRestTemplate} backs onto the JDK's
     * {@code HttpURLConnection}, which rejects {@code PATCH} and mishandles error
     * responses to streamed POSTs. Swap in the JDK {@code HttpClient}-based
     * factory (built into Spring Framework, no extra dependency) so the full verb
     * set and 4xx/5xx status codes come back cleanly over the wire.
     */
    @BeforeEach
    void useJdkHttpClientFactory() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    // ---- helpers ----------------------------------------------------------

    /** Absolute URL for a server-relative path, on the live random port. */
    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** JSON request headers carrying a bearer token. */
    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    /** Sends a request with a JSON body (or null) and returns the raw response. */
    private ResponseEntity<String> exchange(HttpMethod method, String path, String token, Object body)
            throws Exception {
        String json = body == null ? null : objectMapper.writeValueAsString(body);
        HttpEntity<String> entity = new HttpEntity<>(json, bearer(token));
        return rest.exchange(url(path), method, entity, String.class);
    }

    private JsonNode parse(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    /** Logs in over HTTP and returns the issued JWT (no "Bearer " prefix). */
    private String login(String username, String password) throws Exception {
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("username", username, "password", password));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response).get("token").asText();
    }

    /** Registers a fresh USER over HTTP and returns {username, token}. */
    private String[] registerUser() throws Exception {
        String username = "e2e-" + UUID.randomUUID();
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/auth/register", null,
                Map.of("username", username, "password", "secret123"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new String[]{username, parse(response).get("token").asText()};
    }

    /** Registers a fresh USER over HTTP and returns just the token. */
    private String registerUserToken() throws Exception {
        return registerUser()[1];
    }

    /** Creates a contact as the token holder and returns its generated id. */
    private long createContact(String token, String first, String last, String email) throws Exception {
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/contacts", token,
                Map.of("firstName", first, "lastName", last, "email", email));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response).get("id").asLong();
    }

    // ---- auth + contact lifecycle over real HTTP --------------------------

    @Test
    void login_thenFullContactLifecycle_overHttp() throws Exception {
        String token = login("admin", "admin123");

        // Token unlocks the protected list endpoint.
        ResponseEntity<String> list = exchange(HttpMethod.GET, "/api/v1/contacts", token, null);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(list).get("content").isArray()).isTrue();

        String email = "lifecycle-" + UUID.randomUUID() + "@example.com";

        // POST -> 201, with a Location header pointing at the new resource.
        ResponseEntity<String> created = exchange(HttpMethod.POST, "/api/v1/contacts", token,
                Map.of("firstName", "Grace", "lastName", "Hopper", "email", email));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = parse(created).get("id").asLong();
        assertThat(created.getHeaders().getLocation()).isNotNull();
        assertThat(created.getHeaders().getLocation().getPath()).endsWith("/api/v1/contacts/" + id);

        // GET -> 200 with the persisted fields.
        ResponseEntity<String> fetched = exchange(HttpMethod.GET, "/api/v1/contacts/" + id, token, null);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(fetched).get("firstName").asText()).isEqualTo("Grace");
        assertThat(parse(fetched).get("email").asText()).isEqualTo(email);

        // PUT (full replace) -> 200, change reflected.
        ResponseEntity<String> replaced = exchange(HttpMethod.PUT, "/api/v1/contacts/" + id, token,
                Map.of("firstName", "Grace", "lastName", "Murray Hopper", "email", email));
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(replaced).get("lastName").asText()).isEqualTo("Murray Hopper");

        // PATCH (partial) -> 200, only the supplied field changes.
        ResponseEntity<String> patched = exchange(HttpMethod.PATCH, "/api/v1/contacts/" + id, token,
                Map.of("company", "Yale"));
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(patched).get("company").asText()).isEqualTo("Yale");
        assertThat(parse(patched).get("lastName").asText()).isEqualTo("Murray Hopper");

        // DELETE -> 204, then GET -> 404.
        ResponseEntity<String> deleted = exchange(HttpMethod.DELETE, "/api/v1/contacts/" + id, token, null);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<String> gone = exchange(HttpMethod.GET, "/api/v1/contacts/" + id, token, null);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void protectedEndpoint_withoutToken_returns401_overHttp() throws Exception {
        ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/v1/contacts", null, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_withWrongPassword_returns401_overHttp() throws Exception {
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("username", "admin", "password", "wrong-password"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- cross-user isolation + 403 over real HTTP ------------------------

    @Test
    void user_cannotSeeAnotherUsersContact_returns404_overHttp() throws Exception {
        String userA = registerUserToken();
        String userB = registerUserToken();
        long bContact = createContact(userB, "Bob", "Owner", "b-" + UUID.randomUUID() + "@example.com");

        // A cannot read B's contact: 404 hides its very existence.
        ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/v1/contacts/" + bContact, userA, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // ...while B still sees their own.
        ResponseEntity<String> own = exchange(HttpMethod.GET, "/api/v1/contacts/" + bContact, userB, null);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void user_cannotAccessAdminEndpoints_returns403_overHttp() throws Exception {
        String userToken = registerUserToken();

        // Admin-only user management is forbidden to an ordinary USER.
        ResponseEntity<String> users = exchange(HttpMethod.GET, "/api/v1/users", userToken, null);
        assertThat(users.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // ...as is the admin-only audit log.
        ResponseEntity<String> audit = exchange(HttpMethod.GET, "/api/v1/audit", userToken, null);
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- admin users + audit over real HTTP -------------------------------

    @Test
    void admin_canListUsersAndAuditLog_overHttp() throws Exception {
        // A registration emits an audit event, so there is at least one entry.
        String[] user = registerUser();
        String admin = login("admin", "admin123");

        // Admin user listing includes the seeded admin account.
        ResponseEntity<String> users = exchange(HttpMethod.GET, "/api/v1/users", admin, null);
        assertThat(users.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode userArray = parse(users);
        assertThat(userArray.isArray()).isTrue();
        boolean hasAdmin = false;
        boolean hasNewUser = false;
        for (JsonNode node : userArray) {
            if ("admin".equals(node.get("username").asText())) {
                hasAdmin = true;
                assertThat(node.get("role").asText()).isEqualTo("ADMIN");
            }
            if (user[0].equals(node.get("username").asText())) {
                hasNewUser = true;
            }
        }
        assertThat(hasAdmin).isTrue();
        assertThat(hasNewUser).isTrue();

        // Admin audit listing returns a newest-first page with content.
        ResponseEntity<String> audit = exchange(HttpMethod.GET, "/api/v1/audit", admin, null);
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = parse(audit).get("content");
        assertThat(content).isNotNull();
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isGreaterThan(0);
    }

    // ---- actuator health over real HTTP -----------------------------------

    @Test
    void actuatorHealth_isPublic_andReportsUp_overHttp() throws Exception {
        // Public liveness/readiness probe: no token, top-level status only.
        ResponseEntity<String> anon = exchange(HttpMethod.GET, "/actuator/health", null, null);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode anonBody = parse(anon);
        assertThat(anonBody.get("status").asText()).isEqualTo("UP");
        assertThat(anonBody.has("components")).isFalse();

        // An authenticated caller additionally sees component detail.
        String admin = login("admin", "admin123");
        ResponseEntity<String> authed = exchange(HttpMethod.GET, "/actuator/health", admin, null);
        assertThat(authed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(authed).has("components")).isTrue();
    }

    @Test
    void actuatorMetrics_requiresToken_overHttp() throws Exception {
        ResponseEntity<String> anon = exchange(HttpMethod.GET, "/actuator/metrics", null, null);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String admin = login("admin", "admin123");
        ResponseEntity<String> authed = exchange(HttpMethod.GET, "/actuator/metrics", admin, null);
        assertThat(authed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(authed).get("names").isArray()).isTrue();
    }
}
