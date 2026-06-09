package com.example.contacts;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the real proxy-trust path for HTTPS detection (CD-027). With
 * {@code server.forward-headers-strategy=framework} — the setting the TLS overlay
 * (docker-compose.tls.yml) applies when the app runs behind Caddy — an
 * {@code X-Forwarded-Proto: https} header marks the request secure, so the app
 * emits HSTS. Without that header the request is plain HTTP and HSTS is withheld.
 *
 * <p>This complements {@link SecurityIntegrationTest}, which uses
 * {@code .secure(true)} and therefore exercises the {@code HstsHeaderWriter}
 * directly rather than the {@code ForwardedHeaderFilter} that drives it in
 * production.
 */
@SpringBootTest(properties = "server.forward-headers-strategy=framework")
@AutoConfigureMockMvc
class ForwardedHeadersHstsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void forwardedProtoHttps_marksRequestSecure_emitsHsts() throws Exception {
        mockMvc.perform(get("/").header("X-Forwarded-Proto", "https"))
                .andExpect(header().string("Strict-Transport-Security",
                        containsString("max-age=31536000")));
    }

    @Test
    void noForwardedProto_staysHttp_noHsts() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
}
