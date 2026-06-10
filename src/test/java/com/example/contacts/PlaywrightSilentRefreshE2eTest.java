package com.example.contacts;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Browser proof of the CD-028 silent-refresh UX: with a deliberately tiny
 * access-token lifetime (3s) a signed-in user keeps working past expiry with
 * no redirect to the login page — the shared auth client refreshes behind the
 * scenes — and a real logout returns to the login page with credentials gone.
 *
 * <p>Tagged {@code e2e}: excluded from the default build, runs via e2e.yml.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.jwt.expiration-ms=3000")
class PlaywrightSilentRefreshE2eTest {

    @LocalServerPort
    private int port;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    @BeforeAll
    void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterAll
    void closeBrowser() {
        if (context != null) context.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void sessionSurvivesAccessTokenExpiry_andLogoutIsReal() {
        // Sign in as the seeded admin; admins land on the dashboard.
        page.navigate(url("/login.html"));
        page.fill("#username", "admin");
        page.fill("#password", "admin123");
        page.click("#auth-submit");
        page.waitForURL("**/dashboard.html");

        String tokenAtLogin = (String) page.evaluate("() => localStorage.getItem('auth_token')");

        // Outlive the 3s access token, then use the app: the contacts page must
        // load its data (silent refresh) instead of bouncing to login.html.
        page.waitForTimeout(4000);
        page.click("a[href='index.html']");
        page.waitForURL("**/index.html");
        assertThat(page.locator("#contacts-scope")).containsText("Admin view");
        assertThat(page.locator("#contacts-body tr").first()).isVisible();

        // The bearer token visibly rotated — proof the refresh actually ran.
        String tokenAfter = (String) page.evaluate("() => localStorage.getItem('auth_token')");
        org.assertj.core.api.Assertions.assertThat(tokenAfter).isNotBlank().isNotEqualTo(tokenAtLogin);

        // Real logout: back to the login page with all credentials cleared.
        page.click("#btn-logout");
        page.waitForURL("**/login.html");
        String tokenAfterLogout = (String) page.evaluate("() => localStorage.getItem('auth_token')");
        String refreshAfterLogout = (String) page.evaluate("() => localStorage.getItem('auth_refresh_token')");
        org.assertj.core.api.Assertions.assertThat(tokenAfterLogout).isNull();
        org.assertj.core.api.Assertions.assertThat(refreshAfterLogout).isNull();
    }
}
