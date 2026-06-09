package com.example.contacts;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Postgres-backed browser end-to-end test (CD-042). Unlike {@link PlaywrightE2eTest}
 * (which runs on in-memory H2), this boots the app under the real {@code postgres}
 * profile against a throwaway PostgreSQL container (Testcontainers +
 * {@link ServiceConnection}), so it exercises the stack the way production runs it:
 * Flyway applies {@code V1__init.sql}, Hibernate {@code validate} passes, and the
 * app is driven in a real headless-Chromium browser.
 *
 * <p>This is the path H2 tests can't cover. It is exactly the test that surfaced
 * the CD-042 tag-filter bug: {@code SELECT DISTINCT ... ORDER BY LOWER(t)} is
 * accepted by H2 but rejected by PostgreSQL, which made {@code GET /contacts/tags}
 * 500 → the SPA bounced every user back to the login page. Loading the contacts
 * page in a browser against Postgres now proves that path works.
 *
 * <p>The contact <strong>photo</strong> is round-tripped through Postgres
 * ({@code bytea}) via Playwright's API request client rather than the file-input
 * UI: headless Chromium rejects the multipart upload to the local HTTP/1.1 server
 * with {@code net::ERR_H2_OR_QUIC_REQUIRED} (a browser networking quirk, not an app
 * bug — the same upload works via curl and a real browser). The API client drives
 * the identical server endpoint, so the {@code bytea} persistence is still proven.
 *
 * <p><strong>Not part of the default build.</strong> Tagged {@code "e2e"} and
 * excluded from {@code mvn verify}; runs only via {@code .github/workflows/e2e.yml}
 * on develop/master. Needs Docker (Testcontainers) + a downloaded Chromium — both
 * present on the CI runner; run locally with a one-off
 * {@code mvn exec:java ... "install chromium"} then
 * {@code ./mvnw test -Dtest=PlaywrightPostgresE2eTest -Dtest.excludedGroups=}.
 */
@Tag("e2e")
@Testcontainers
@ActiveProfiles("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlaywrightPostgresE2eTest {

    /** Where screenshots and the session video are written for human review. */
    private static final Path EVIDENCE_DIR = Paths.get("target", "playwright");

    /** A valid 1x1 PNG (same fixture shape as the API tests). */
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC");

    private static final Pattern FIRST_ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    /**
     * Real PostgreSQL for this test class. {@link ServiceConnection} publishes the
     * container's JDBC url/user/password to Spring, overriding the {@code postgres}
     * profile's placeholder datasource — so Flyway + Hibernate run against it.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    private int port;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    private final AtomicInteger shot = new AtomicInteger(0);

    @BeforeAll
    void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
        context = browser.newContext(new Browser.NewContextOptions()
                .setRecordVideoDir(EVIDENCE_DIR)
                .setViewportSize(1280, 900));
        page = context.newPage();
    }

    @AfterAll
    void closeBrowser() {
        if (context != null) {
            context.close();
        }
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void screenshot(String name) {
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(EVIDENCE_DIR.resolve(String.format("%02d-%s.png", shot.incrementAndGet(), name)))
                .setFullPage(true));
    }

    private void signInAsAdmin() {
        page.navigate(url("/login.html"));
        assertThat(page).hasTitle(Pattern.compile("Sign in"));
        page.fill("#username", "admin");
        page.fill("#password", "admin123");
        page.click("#auth-submit");
        page.waitForURL("**/index.html");
    }

    /**
     * Drive the app in a browser against Postgres and round-trip a contact photo
     * through the {@code bytea} column.
     */
    @Test
    void appRunsInABrowserOnPostgresAndPhotoRoundTrips() {
        // 1. Browser: the contacts page loads on Postgres. This depends on
        //    GET /contacts AND GET /contacts/tags succeeding (the latter is the
        //    query that is invalid on Postgres if unfixed); a failure there would
        //    bounce the SPA back to the login page and this would not be reached.
        signInAsAdmin();
        assertThat(page.locator(".app-header__title")).hasText("Contact Directory");
        assertThat(page.locator("#contacts-body tr")).hasCount(3);   // seeded by DataInitializer
        assertThat(page.locator("#link-users")).isVisible();          // admin nav rendered
        screenshot("pg-01-contacts");

        // 2. Photo bytea round-trip on Postgres via Playwright's API request client
        //    (the browser file-input multipart upload hits the headless quirk noted
        //    in the class javadoc; this exercises the same server endpoints).
        String token = (String) page.evaluate("() => localStorage.getItem('auth_token')");
        assertNotNull(token, "the SPA should store a JWT after login");
        String auth = "Bearer " + token;

        APIResponse list = page.request().get(url("/api/v1/contacts?size=1"),
                RequestOptions.create().setHeader("Authorization", auth));
        assertEquals(200, list.status(), "contacts list should work on Postgres");
        Matcher m = FIRST_ID.matcher(list.text());
        assertTrue(m.find(), "a seeded contact id should be present");
        long id = Long.parseLong(m.group(1));

        APIResponse upload = page.request().post(url("/api/v1/contacts/" + id + "/photo"),
                RequestOptions.create().setHeader("Authorization", auth)
                        .setMultipart(FormData.create()
                                .set("file", new FilePayload("photo.png", "image/png", TINY_PNG))));
        assertEquals(200, upload.status(), "photo upload should persist to Postgres bytea");

        APIResponse fetched = page.request().get(url("/api/v1/contacts/" + id + "/photo"),
                RequestOptions.create().setHeader("Authorization", auth));
        assertEquals(200, fetched.status(), "the stored photo should be served back");
        assertArrayEquals(TINY_PNG, fetched.body(), "photo bytes must round-trip via Postgres bytea");

        // 3. Back in the browser: reload and confirm the uploaded photo actually
        //    RENDERS (CD-043 fix): the avatar is fetched with the bearer token,
        //    shown as a blob object URL, and decodes (naturalWidth > 0) — not the
        //    initials placeholder.
        page.reload();
        assertThat(page.locator("#contacts-body img.avatar").first()).isVisible();
        page.waitForFunction(
                "() => { const i = document.querySelector('#contacts-body img.avatar');"
              + " return !!i && i.complete && i.naturalWidth > 0; }");
        screenshot("pg-02-photo-rendered");
    }
}
