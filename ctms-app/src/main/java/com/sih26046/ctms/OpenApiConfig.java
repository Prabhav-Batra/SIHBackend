package com.sih26046.ctms;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the CSRF header (§18.12) as a named security scheme so Swagger UI's Authorize
 * button can hold it once per session, the same way the browser's own cookie jar already holds
 * the login cookies automatically.
 *
 * <p>The value has to come from the browser's own {@code csrf_token} cookie — set it by opening
 * devtools → Application → Cookies after logging in via {@code POST /api/v1/auth/login}, then
 * paste it into Authorize here. That manual step is the one place a real frontend does
 * something Swagger UI cannot: read a non-HttpOnly cookie in JavaScript and attach it itself.
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_NAME = "csrfToken";

    @Bean
    public OpenAPI ctmsOpenApi() {
        return new OpenAPI()
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        SCHEME_NAME,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name("X-CSRF-Token")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME));
    }
}
