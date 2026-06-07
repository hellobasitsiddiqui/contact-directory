package com.example.contacts.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger configuration for the Contact Directory API.
 *
 * <p>Exposes the API metadata consumed by springdoc to render the
 * Swagger UI (available at {@code /swagger-ui.html}) and the OpenAPI
 * document (available at {@code /v3/api-docs}).
 *
 * <p>Declares a {@code bearerAuth} JWT security scheme so Swagger UI shows an
 * <em>Authorize</em> button: paste a token from {@code /api/v1/auth/login} and
 * the UI sends it on every request. The public auth endpoints still work
 * without a token even though they are marked as secured.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    /**
     * Builds the {@link OpenAPI} bean describing this service, including the
     * JWT bearer security scheme applied to the API.
     *
     * @return the OpenAPI definition with metadata and security scheme
     */
    @Bean
    public OpenAPI contactDirectoryOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Contact Directory API")
                        .version("1.0")
                        .description("REST API for managing contacts in the Contact Directory. "
                                + "Most endpoints require a JWT bearer token obtained from "
                                + "POST /api/v1/auth/login or /api/v1/auth/register."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste a JWT from /api/v1/auth/login "
                                        + "(the 'Bearer ' prefix is added automatically).")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
