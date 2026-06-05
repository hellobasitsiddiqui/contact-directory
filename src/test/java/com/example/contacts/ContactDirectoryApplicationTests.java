package com.example.contacts;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test verifying that the full Spring application context starts
 * successfully with all beans, JPA configuration and seed data in place.
 */
@SpringBootTest
class ContactDirectoryApplicationTests {

    /**
     * Fails if the application context cannot be loaded.
     */
    @Test
    void contextLoads() {
        // Intentionally empty: success is the context starting without error.
    }
}
