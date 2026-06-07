package com.example.contacts;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Targeted tests that exercise edge branches the happy-path suite leaves
 * uncovered (identified from the JaCoCo branch report): full PATCH field
 * application, PATCH duplicate-email, double soft-delete no-op, CSV import
 * variants (positional/headerless, blank lines, boolean spellings, comma/empty
 * tags, short rows, CRLF + quoted/escaped fields), bulk-tags add-only /
 * remove-only, and CSV export quoting of special characters.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BranchCoverageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper om;

    private String uniq() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private Map<String, Object> base(String email) {
        Map<String, Object> m = new HashMap<>();
        m.put("firstName", "A");
        m.put("lastName", "B");
        m.put("email", email);
        return m;
    }

    private long create(Map<String, Object> body) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode importCsv(String csv) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "c.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        MvcResult r = mockMvc.perform(multipart("/api/v1/contacts/import").file(file))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    // --- PATCH branches -----------------------------------------------------

    @Test
    void patch_allFieldsSet_appliesEveryField() throws Exception {
        long id = create(base(uniq()));
        Map<String, Object> p = new HashMap<>();
        p.put("firstName", "Ada");
        p.put("lastName", "Lovelace");
        p.put("email", uniq());
        p.put("phone", "+1 555 000 1234");
        p.put("company", "Analytical Engines");
        p.put("tags", List.of("Maths", "Pioneer"));
        p.put("favorite", true);
        p.put("notes", "first programmer");
        mockMvc.perform(patch("/api/v1/contacts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(p)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.lastName").value("Lovelace"))
                .andExpect(jsonPath("$.phone").value("+1 555 000 1234"))
                .andExpect(jsonPath("$.company").value("Analytical Engines"))
                .andExpect(jsonPath("$.favorite").value(true))
                .andExpect(jsonPath("$.notes").value("first programmer"))
                .andExpect(jsonPath("$.tags", hasItem("Maths")));
    }

    @Test
    void patch_emailToAnExistingEmail_returns409() throws Exception {
        String taken = uniq();
        create(base(taken));
        long other = create(base(uniq()));
        Map<String, Object> p = new HashMap<>();
        p.put("email", taken);
        mockMvc.perform(patch("/api/v1/contacts/{id}", other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(p)))
                .andExpect(status().isConflict());
    }

    // --- soft-delete idempotency -------------------------------------------

    @Test
    void delete_twice_secondIsNoOp() throws Exception {
        long id = create(base(uniq()));
        mockMvc.perform(delete("/api/v1/contacts/{id}", id)).andExpect(status().isNoContent());
        // Second soft-delete on an already-trashed contact hits the no-op branch.
        mockMvc.perform(delete("/api/v1/contacts/{id}", id)).andExpect(status().isNoContent());
        // Still recoverable -> restore succeeds (proves it stayed a single trashed row).
        mockMvc.perform(post("/api/v1/contacts/{id}/restore", id)).andExpect(status().isOk());
    }

    // --- CSV import variants -----------------------------------------------

    @Test
    void import_headerlessPositionalRow_isImported() throws Exception {
        String email = uniq();
        // No header line: detected as positional firstName,lastName,email,phone,company,tags,favorite,notes
        String csv = "Ada,Lovelace," + email + ",,Analytical,Work;VIP,true,a note\n";
        JsonNode s = importCsv(csv);
        org.junit.jupiter.api.Assertions.assertEquals(1, s.get("imported").asInt());
        mockMvc.perform(get("/api/v1/contacts").param("search", email))
                .andExpect(jsonPath("$.content[0].firstName").value("Ada"))
                .andExpect(jsonPath("$.content[0].favorite").value(true))
                .andExpect(jsonPath("$.content[0].tags", hasItem("VIP")));
    }

    @Test
    void import_blankAndWhitespaceLinesAreSkipped() throws Exception {
        String email = uniq();
        String csv = "firstName,lastName,email\n"
                + "\n"
                + "   \n"
                + "Grace,Hopper," + email + "\n";
        JsonNode s = importCsv(csv);
        org.junit.jupiter.api.Assertions.assertEquals(1, s.get("imported").asInt());
    }

    @Test
    void import_booleanSpellingsForFavorite() throws Exception {
        String e1 = uniq();
        String e2 = uniq();
        String e3 = uniq();
        String e4 = uniq();
        String csv = "firstName,lastName,email,favorite\n"
                + "One,X," + e1 + ",1\n"
                + "Two,X," + e2 + ",yes\n"
                + "Three,X," + e3 + ",y\n"
                + "Four,X," + e4 + ",nope\n";
        JsonNode s = importCsv(csv);
        org.junit.jupiter.api.Assertions.assertEquals(4, s.get("imported").asInt());
        for (String e : new String[]{e1, e2, e3}) {
            mockMvc.perform(get("/api/v1/contacts").param("search", e))
                    .andExpect(jsonPath("$.content[0].favorite").value(true));
        }
        mockMvc.perform(get("/api/v1/contacts").param("search", e4))
                .andExpect(jsonPath("$.content[0].favorite").value(false));
    }

    @Test
    void import_tagsWithCommaSeparatorsAndEmptyParts() throws Exception {
        String email = uniq();
        // tags cell quoted (contains commas); empty parts (";;") must be dropped.
        String csv = "firstName,lastName,email,tags\n"
                + "Tag,Person," + email + ",\"Work, ,VIP;;Lead\"\n";
        JsonNode s = importCsv(csv);
        org.junit.jupiter.api.Assertions.assertEquals(1, s.get("imported").asInt());
        mockMvc.perform(get("/api/v1/contacts").param("search", email))
                .andExpect(jsonPath("$.content[0].tags", hasItem("Work")))
                .andExpect(jsonPath("$.content[0].tags", hasItem("VIP")))
                .andExpect(jsonPath("$.content[0].tags", hasItem("Lead")));
    }

    @Test
    void import_shortRowMissingTrailingColumns() throws Exception {
        String email = uniq();
        // Header declares 8 columns; the data row supplies only 3 (short row).
        String csv = "firstName,lastName,email,phone,company,tags,favorite,notes\n"
                + "Short,Row," + email + "\n";
        JsonNode s = importCsv(csv);
        org.junit.jupiter.api.Assertions.assertEquals(1, s.get("imported").asInt());
        mockMvc.perform(get("/api/v1/contacts").param("search", email))
                .andExpect(jsonPath("$.content[0].firstName").value("Short"))
                .andExpect(jsonPath("$.content[0].phone").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void import_crlfWithQuotedCommaAndEscapedQuotes() throws Exception {
        String email = uniq();
        // CRLF line endings; company field quoted, contains a comma and an escaped (doubled) quote.
        String csv = "firstName,lastName,email,company\r\n"
                + "Quote,Man," + email + ",\"Acme, \"\"Inc\"\"\"\r\n";
        JsonNode s = importCsv(csv);
        org.junit.jupiter.api.Assertions.assertEquals(1, s.get("imported").asInt());
        mockMvc.perform(get("/api/v1/contacts").param("search", email))
                .andExpect(jsonPath("$.content[0].company").value("Acme, \"Inc\""));
    }

    // --- bulk tags add-only / remove-only ----------------------------------

    @Test
    void bulkTags_addOnly_thenRemoveOnly() throws Exception {
        long id = create(base(uniq()));

        Map<String, Object> addOnly = new HashMap<>();
        addOnly.put("ids", List.of(id));
        addOnly.put("addTags", List.of("Alpha", "Beta"));
        // no removeTags key -> null on the server
        mockMvc.perform(post("/api/v1/contacts/bulk/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(addOnly)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(1));

        Map<String, Object> removeOnly = new HashMap<>();
        removeOnly.put("ids", List.of(id));
        removeOnly.put("removeTags", List.of("alpha")); // case-insensitive removal
        mockMvc.perform(post("/api/v1/contacts/bulk/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(removeOnly)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(1));

        mockMvc.perform(get("/api/v1/contacts/{id}", id))
                .andExpect(jsonPath("$.tags", hasItem("Beta")));
    }

    // --- CSV export quoting -------------------------------------------------

    @Test
    void exportCsv_quotesFieldsWithSpecialCharacters() throws Exception {
        Map<String, Object> c = base(uniq());
        c.put("company", "Acme, \"Inc\""); // comma + quotes -> must be CSV-escaped on export
        create(c);
        mockMvc.perform(get("/api/v1/contacts/export.csv"))
                .andExpect(status().isOk())
                // escaped form: surrounding quotes + doubled inner quotes
                .andExpect(content().string(containsString("\"Acme, \"\"Inc\"\"\"")));
    }

    // --- controller upload validation branches ------------------------------

    @Test
    void uploadPhoto_emptyFile_returns400() throws Exception {
        long id = create(base(uniq()));
        MockMultipartFile empty = new MockMultipartFile("file", "x.png", "image/png", new byte[0]);
        mockMvc.perform(multipart("/api/v1/contacts/{id}/photo", id).file(empty))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadPhoto_nullContentType_returns400() throws Exception {
        long id = create(base(uniq()));
        MockMultipartFile noType = new MockMultipartFile("file", "x.png", null, new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/v1/contacts/{id}/photo", id).file(noType))
                .andExpect(status().isBadRequest());
    }

    @Test
    void import_emptyFile_returns400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "x.csv", "text/csv", new byte[0]);
        mockMvc.perform(multipart("/api/v1/contacts/import").file(empty))
                .andExpect(status().isBadRequest());
    }

    @Test
    void import_acceptedByCsvExtension_whenContentTypeNotCsv() throws Exception {
        String email = uniq();
        String csv = "firstName,lastName,email\nExt,Person," + email + "\n";
        MockMultipartFile f = new MockMultipartFile(
                "file", "data.csv", "application/octet-stream", csv.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/contacts/import").file(f))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    void import_acceptedByCsvExtension_whenContentTypeNull() throws Exception {
        String email = uniq();
        String csv = "firstName,lastName,email\nNull,Type," + email + "\n";
        MockMultipartFile f = new MockMultipartFile(
                "file", "data.csv", null, csv.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/contacts/import").file(f))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
    }
}
