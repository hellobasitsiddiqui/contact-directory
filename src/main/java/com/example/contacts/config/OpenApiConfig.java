package com.example.contacts.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger configuration for the Contact Directory API.
 *
 * <p>Exposes the API metadata consumed by springdoc to render the
 * Swagger UI (available at {@code /swagger-ui.html}) and the OpenAPI
 * document (available at {@code /v3/api-docs}).
 */
@Configuration
public class OpenApiConfig {

    /**
     * Builds the {@link OpenAPI} bean describing this service.
     *
     * @return the OpenAPI definition with title and version metadata
     */
    @Bean
    public OpenAPI contactDirectoryOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Contact Directory API")
                        .version("1.0")
                        .description("REST API for managing contacts in the Contact Directory."));
    }
}
