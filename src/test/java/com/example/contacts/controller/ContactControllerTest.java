package com.example.contacts.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.contacts.dto.ContactRequest;
import com.example.contacts.dto.ContactResponse;
import com.example.contacts.exception.ResourceNotFoundException;
import com.example.contacts.service.ContactService;
import com.example.contacts.service.PhotoData;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice tests for {@link ContactController}. Only the MVC
 * infrastructure is loaded; {@link ContactService} is mocked via
 * {@link MockBean}.
 *
 * <p>{@code @WebMvcTest} does not auto-register Spring Data's {@code Pageable}
 * argument resolver, so {@link SpringDataWebAutoConfiguration} is imported
 * explicitly to support the paginated list endpoint.
 */
@WebMvcTest(ContactController.class)
@Import(SpringDataWebAutoConfiguration.class)
class ContactControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ContactService contactService;

    private ContactResponse sampleResponse() {
        Instant now = Instant.now();
        return new ContactResponse(1L, "Ada", "Lovelace", "ada@example.com",
                "+44 20 7946 0958", "Analytical Engines", now, now, null, java.util.Set.of(), false, null);
    }

    @Test
    void post_validBody_returns201WithLocationHeaderAndBody() throws Exception {
        ContactRequest req = new ContactRequest("Ada", "Lovelace", "ada@example.com",
                "+44 20 7946 0958", "Analytical Engines", null, false, null);
        when(contactService.create(any(ContactRequest.class))).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/contacts/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("ada@example.com"));
    }

    @Test
    void post_blankFirstName_returns400WithFieldError() throws Exception {
        ContactRequest req = new ContactRequest("", "Lovelace", "ada@example.com", null, null, null, false, null);

        mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors.firstName").exists());
    }

    @Test
    void post_invalidEmail_returns400WithFieldError() throws Exception {
        ContactRequest req = new ContactRequest("Ada", "Lovelace", "not-an-email", null, null, null, false, null);

        mockMvc.perform(post("/api/v1/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    void get_existingId_returns200WithBody() throws Exception {
        when(contactService.get(1L)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/contacts/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.firstName").value("Ada"));
    }

    @Test
    void get_missingId_returns404() throws Exception {
        when(contactService.get(99L))
                .thenThrow(new ResourceNotFoundException("Contact not found with id: 99"));

        mockMvc.perform(get("/api/v1/contacts/{id}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Contact not found with id: 99"));
    }

    @Test
    void get_list_returns200WithPagedContent() throws Exception {
        ContactResponse resp = sampleResponse();
        Page<ContactResponse> page = new PageImpl<>(List.of(resp));
        when(contactService.list(any(), any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/contacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/contacts/{id}", 1L))
                .andExpect(status().isNoContent());

        verify(contactService).delete(eq(1L));
    }

    // ---- Photo endpoints -------------------------------------------------

    @Test
    void postPhoto_validPng_returns200AndSavesPhoto() throws Exception {
        byte[] bytes = {(byte) 0x89, 'P', 'N', 'G'};
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.png", "image/png", bytes);
        when(contactService.get(1L)).thenReturn(sampleResponse());

        mockMvc.perform(multipart("/api/v1/contacts/{id}/photo", 1L).file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));

        verify(contactService).savePhoto(eq(1L), eq(bytes), eq("image/png"));
    }

    @Test
    void postPhoto_unsupportedType_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.txt", "text/plain", "not an image".getBytes());

        mockMvc.perform(multipart("/api/v1/contacts/{id}/photo", 1L).file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getPhoto_existing_returns200WithImageContentType() throws Exception {
        byte[] bytes = {(byte) 0x89, 'P', 'N', 'G'};
        when(contactService.getPhoto(1L)).thenReturn(new PhotoData(bytes, "image/png"));

        mockMvc.perform(get("/api/v1/contacts/{id}/photo", 1L))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void getPhoto_missing_returns404() throws Exception {
        when(contactService.getPhoto(99L))
                .thenThrow(new ResourceNotFoundException("No photo for contact with id: 99"));

        mockMvc.perform(get("/api/v1/contacts/{id}/photo", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void deletePhoto_returns204AndDeletesPhoto() throws Exception {
        mockMvc.perform(delete("/api/v1/contacts/{id}/photo", 1L))
                .andExpect(status().isNoContent());

        verify(contactService).deletePhoto(eq(1L));
    }
}
