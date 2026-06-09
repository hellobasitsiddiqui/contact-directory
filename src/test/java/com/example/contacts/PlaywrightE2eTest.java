package com.example.contacts;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Browser end-to-end test (CD-018). Boots the real application on a random port
 * and drives the actual web UI in a headless Chromium browser via
 * <a href="https://playwright.dev/java/">Playwright for Java</a>, walking the
 * documented happy path across every page: login → contacts → users → activity
 * → profile.
 *
 * <p>Unlike {@link HttpEndToEndTest} (which exercises the API over HTTP with
 * {@code TestRestTemplate}), this drives the front-end the way a person would —
 * typing into the login form, clicking nav links, reading rendered tables — so
 * it covers the static HTML/CSS/JS in {@code src/main/resources/static} together
 * with the live backend.
 *
 * <p><strong>Evidence:</strong> a numbered screenshot is saved after each page
 * and a full-session video is recorded; both land under {@code target/playwright/}
 * for human review (and the e2e CI workflow uploads them as an artifact).
 *
 * <p><strong>Not part of the default build.</strong> The class is tagged
 * {@code "e2e"} and excluded from {@code mvn verify} via the surefire
 * {@code <excludedGroups>} in {@code pom.xml}; it needs a downloaded browser and
 * runs only via the dedicated {@code .github/workflows/e2e.yml} workflow on
 * develop/master (or locally with
 * {@code ./mvnw test -Dtest.excludedGroups= -Dgroups=e2e}, after a one-off
 * {@code mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI
 * -Dexec.classpathScope=test -Dexec.args="install chromium"}).
 *
 * <p>Credentials are the seeded admin (admin/admin123) from {@code DataInitializer}
 * plus the sample contacts; the in-memory test database is recreated per context.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlaywrightE2eTest {

    /** Where screenshots and videos are written for human review. */
    private static final Path EVIDENCE_DIR = Paths.get("target", "playwright");

    @LocalServerPort
    private int port;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    /** Drives the screenshot file numbering (01-…, 02-…) in capture order. */
    private final AtomicInteger shot = new AtomicInteger(0);

    @BeforeAll
    void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
        // Record a video of the whole session into target/playwright/ as evidence.
        context = browser.newContext(new Browser.NewContextOptions()
                .setRecordVideoDir(EVIDENCE_DIR)
                .setViewportSize(1280, 900));
        page = context.newPage();
    }

    @AfterAll
    void closeBrowser() {
        // Closing the context flushes the recorded video to disk.
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

    // ---- helpers ----------------------------------------------------------

    /** Absolute URL for a server-relative path, on the live random port. */
    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * Saves a numbered, full-page screenshot to {@code target/playwright/} so the
     * run leaves a human-reviewable trail (NN-name.png, in capture order).
     */
    private void screenshot(String name) {
        String file = String.format("%02d-%s.png", shot.incrementAndGet(), name);
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(EVIDENCE_DIR.resolve(file))
                .setFullPage(true));
    }

    /** Logs in through the real login form as the seeded admin. */
    private void signInAsAdmin() {
        page.navigate(url("/login.html"));
        assertThat(page).hasTitle(java.util.regex.Pattern.compile("Sign in"));
        screenshot("login");

        page.fill("#username", "admin");
        page.fill("#password", "admin123");
        page.click("#auth-submit");

        // Admins are redirected to the dashboard (user administration) on success.
        page.waitForURL("**/dashboard.html");
    }

    // ---- the walkthrough --------------------------------------------------

    /**
     * One ordered walkthrough across all five pages, asserting the key landmark
     * on each and leaving a numbered screenshot behind. A single flow keeps the
     * (expensive) browser/app boot to one shared session.
     */
    @Test
    void walksTheWholeAppInABrowser() {
        // 1. Login page → sign in as admin (admins land on the dashboard).
        signInAsAdmin();

        // 2. Dashboard: the admin landing page is user administration, not contacts.
        assertThat(page.locator(".app-header__title")).hasText("Dashboard");
        assertThat(page.locator("#user-stats")).isVisible();
        screenshot("dashboard");

        // 3. Contacts: reached via the dashboard's "Contacts" link. The three sample
        // contacts are owned by the seeded sample users; the admin (super-user) sees
        // all of them, and the page shows the "Admin view" scope banner.
        page.click("a[href='index.html']");
        page.waitForURL("**/index.html");
        assertThat(page.locator(".app-header__title")).hasText("Contact Directory");
        assertThat(page.locator("#contacts-scope")).containsText("Admin view");
        assertThat(page.locator("#link-users")).isVisible();
        assertThat(page.locator("#link-activity")).isVisible();
        assertThat(page.locator("#contacts-body tr")).hasCount(3);
        assertThat(page.locator("#contacts-body")).containsText("Jane");
        assertThat(page.locator("#contacts-body")).containsText("Maria");
        screenshot("contacts");

        // 3. Users: navigate via the header link; the admin lists at least itself.
        page.click("#link-users");
        page.waitForURL("**/users.html");
        assertThat(page.locator(".app-header__title")).hasText("User Management");
        // users.js un-hides #users-table only when there are matching rows; this passes
        // because the seeded admin guarantees at least one user.
        assertThat(page.locator("#users-table")).isVisible();
        assertThat(page.locator("#users-body")).containsText("admin");
        screenshot("users");

        // 4. Activity: the audit log shows recorded events (login at minimum).
        page.click("a[href='activity.html']");
        page.waitForURL("**/activity.html");
        assertThat(page.locator(".app-header__title")).hasText("Activity Log");
        // activity.js un-hides #audit-table only when there are matching rows; this passes
        // because the login just performed is recorded as at least one audit event.
        assertThat(page.locator("#audit-table")).isVisible();
        assertThat(page.locator("#audit-body tr").first()).isVisible();
        screenshot("activity");

        // 5. Profile: the account panel shows the signed-in admin's details.
        page.click("a[href='profile.html']");
        page.waitForURL("**/profile.html");
        assertThat(page.locator(".app-header__title")).hasText("My Profile");
        assertThat(page.locator("#profile-username")).hasText("admin");
        assertThat(page.locator("#profile-role")).containsText("ADMIN");
        screenshot("profile");
    }
}
