package com.collabeditor.realtime_editor.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 / Swagger configuration.
 * <p>
 * Exposes interactive API docs at {@code /swagger-ui.html} and the raw spec at
 * {@code /v3/api-docs}. Declares a JWT bearer security scheme so the Swagger UI
 * "Authorize" button lets you paste a token and call protected endpoints.
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI collabIdeOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CollabIDE API")
                        .description("REST API for CollabIDE - a real-time collaborative code editor "
                                + "with JWT auth, room roles, version history, chat, and sandboxed code execution.")
                        .version("v1")
                        .contact(new Contact().name("CollabIDE"))
                        .license(new License().name("MIT")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .name(SECURITY_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the JWT returned by /api/auth/login or /api/auth/register")));
    }
}
