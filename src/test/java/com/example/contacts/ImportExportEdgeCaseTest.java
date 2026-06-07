package com.example.contacts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.contacts.dto.ContactRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-stack edge-case coverage for the CSV/JSON import &amp; export endpoints,
 * run against the real controller, service, repository and an in-memory H2
 * database (seeded by {@code data.sql}).
 *
 * <p>Tests are independent and order-agnostic: every contact uses a unique
 * email, count assertions on shared totals use {@code >=}, and no test relies
 * on deleting any seeded row. Focus areas:
 * <ul>
 *   <li>soft-deleted contacts are excluded from both export formats;</li>
 *   <li>an email belonging to a soft-deleted (trashed) contact stays reserved,
 *       so an import row reusing it is counted as skipped;</li>
 *   <li>CSV special characters (comma, double-quote, newline) survive an
 *       RFC&nbsp;4180 round trip and are preserved byte-for-byte on fetch;</li>
 *   <li>a malformed/blank-required row is reported in {@code errors} while the
 *       valid rows in the same upload still import.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportExportEdgeCaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Generates an email guaranteed not to collide with seeds or other tests. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /**
     * (1) A soft-deleted contact must not appear in either the CSV or JSON
     * export. Create a uniquely-emailed contact, confirm it is exported, then
     * soft-delete it and assert its email is absent from both export bodies.
     */
    @Test
    void exports_excludeSoftDeletedContact() throws Exception {
        String email = uniqueEmail();
        long id = createContact("Trashed", "Export", email, "Acme Excluded");

        // Present in both exports while active.
        mockMvc.perform(get("/api/v1/contacts/export.csv"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(Matchers.containsString(email)));
        mockMvc.perform(get("/api/v1/contacts/export.json"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(Matchers.containsString(email)));

        // Soft delete (moves it to trash).
        mockMvc.perform(delete("/api/v1/contacts/{id}", id))
                .andExpect(status().isNoContent());

        // Now absent from BOTH export formats.
        mockMvc.perform(get("/api/v1/contacts/export.csv"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(Matchers.not(Matchers.containsString(email))));
        mockMvc.perform(get("/api/v1/contacts/export.json"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(Matchers.not(Matchers.containsString(email))));
    }

    /**
     * (2) An email belonging to a soft-deleted contact stays reserved. An
     * import row reusing that trashed email must not create a new contact -- it
     * is counted as skipped (uniqueness is enforced across trashed rows too).
     */
    @Test
    void import_skipsRowWhoseEmailBelongsToSoftDeletedContact() throws Exception {
        String trashedEmail = uniqueEmail();
        long trashedId = createContact("Reserved", "Email", trashedEmail, "Reserved Co");

        // Soft-delete so the email now lives only in the trash.
        mockMvc.perform(delete("/api/v1/contacts/{id}", trashedId))
                .andExpect(status().isNoContent());

        String freshEmail = uniqueEmail();
        String csv = "firstName,lastName,email,phone,company,tags,favorite,notes\r\n"
                // valid, brand-new row -> imported
                + "Fresh,Row," + freshEmail + ",,Globex,,false,\r\n"
                // row reusing the trashed contact's email -> skipped (still reserved)
                + "Ghost,Revenant," + trashedEmail + ",,Initech,,false,\r\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "reserved.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/contacts/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                // the trashed-email row was skipped, not imported and not an error
                .andExpect(jsonPath("$.skipped").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.errors", Matchers.empty()));

        // The trashed email did NOT come back as an active contact.
        mockMvc.perform(get("/api/v1/contacts").param("search", trashedEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // The genuinely new row is now active.
        mockMvc.perform(get("/api/v1/contacts").param("search", freshEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].firstName").value("Fresh"));
    }

    /**
     * (3) CSV special-character round trip. A field containing a comma, a
     * double-quote and a newline, quoted per RFC&nbsp;4180 ("" escapes a quote,
     * the whole field wrapped in quotes), must import successfully and the value
     * must be preserved exactly when the contact is fetched back.
     */
    @Test
    void import_csvSpecialCharacters_roundTripPreservedExactly() throws Exception {
        String email = uniqueEmail();

        // Raw values the user intends to store -- contain comma, quote and newline.
        String company = "Doe, \"The\" Firm, Ltd";
        String notes = "Line one, with comma\nLine two with a \"quote\"\nLine three";

        // Build an RFC 4180 row: every field that contains a comma/quote/newline
        // is wrapped in double quotes, and embedded quotes are doubled.
        String csv = "firstName,lastName,email,phone,company,tags,favorite,notes\r\n"
                + "Special,Chars,"
                + email + ",,"
                + quote(company) + ","
                + ","          // empty tags
                + "false,"
                + quote(notes) + "\r\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "special.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/contacts/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.errors", Matchers.empty()));

        // Locate the imported contact's id via the active list, then fetch it
        // and assert company + notes survived the round trip byte-for-byte.
        long id = findActiveIdByEmail(email);

        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.company").value(company))
                .andExpect(jsonPath("$.notes").value(notes));
    }

    /**
     * (4) A malformed row (missing the required email) is reported in
     * {@code errors}, while the valid rows in the same upload are still
     * imported. Mixing one bad row with two good rows proves partial success.
     */
    @Test
    void import_malformedRow_reportedInErrors_validRowsStillImported() throws Exception {
        String good1 = uniqueEmail();
        String good2 = uniqueEmail();

        String csv = "firstName,lastName,email,phone,company,tags,favorite,notes\r\n"
                + "Valid,One," + good1 + ",,Acme,,false,\r\n"
                // blank-required: no email -> reported as an error, not imported
                + "Broken,Person,,,Globex,,false,\r\n"
                + "Valid,Two," + good2 + ",,Initech,,false,\r\n";

        MockMultipartFile file = new MockMultipartFile(
                "file", "mixed.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/v1/contacts/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.errors", Matchers.hasSize(1)))
                .andReturn();

        // The single error must reference the offending (blank-required) row.
        JsonNode summary = objectMapper.readTree(result.getResponse().getContentAsString());
        String error = summary.get("errors").get(0).asText();
        Assertions.assertThat(error).contains("required");

        // Both valid rows were created and are retrievable.
        mockMvc.perform(get("/api/v1/contacts").param("search", good1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].firstName").value("Valid"));
        mockMvc.perform(get("/api/v1/contacts").param("search", good2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].lastName").value("Two"));
    }

    // ---- Helpers ----------------------------------------------------------

    /** Wraps a field in double quotes, doubling any embedded quotes (RFC 4180). */
    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** Creates a contact via the API and returns its generated id. */
    private long createContact(String first, String last, String email, String company)
            throws Exception {
        ContactRequest req = new ContactRequest(first, last, email,
                null, company, null, false, null, null);
        MvcResult result = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asLong();
    }

    /** Finds the id of the single active contact matching the given email. */
    private long findActiveIdByEmail(String email) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/contacts").param("search", email))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("content");
        Assertions.assertThat(content).isNotNull();
        Assertions.assertThat(content.size()).isGreaterThanOrEqualTo(1);
        return content.get(0).get("id").asLong();
    }
}
