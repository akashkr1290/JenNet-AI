package com.jannetai.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger setup (ARCHITECTURE.md Section 3: "All backend APIs
 * documented via OpenAPI/Swagger. ... the generated spec is the source of
 * truth Flutter codegens/consumes against."). Swagger UI is available at
 * /swagger-ui.html once the app is running; the raw spec at /v3/api-docs.
 *
 * Phase 4 adds the "bearerAuth" JWT security scheme so Swagger UI's
 * "Authorize" button works for the protected endpoints added this phase.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI jannetAiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("JANNet AI - Business Backend API")
                        .description("""
                                Spring Boot business backend for the JANNet AI civic complaint platform. \
                                Base path: /api/v1/... All endpoints except /api/v1/auth/** and the \
                                Gap-backlog Patch 6/8 exceptions below require a JWT bearer token.

                                ## Authentication
                                1. `POST /api/v1/auth/register` (citizen self-registration) or an admin-created \
                                   staff account.
                                2. `POST /api/v1/auth/send-otp` + `POST /api/v1/auth/verify-otp` (mobile verification).
                                3. `POST /api/v1/auth/login` -> `{accessToken, refreshToken}`. Click \
                                   **Authorize** above and paste the access token.
                                4. `POST /api/v1/auth/refresh` when the access token expires; refresh tokens \
                                   rotate on every use (reuse of an already-rotated token revokes the whole chain).

                                ## Typical citizen workflow
                                `POST /api/v1/public/wards` (no token, registration only) -> register/login -> \
                                `POST /api/v1/complaints` (multipart: photo + description + latitude/longitude) \
                                -> `GET /api/v1/complaints/{id}` to track status -> once Resolved/Closed, \
                                optionally `POST /api/v1/complaints/{id}/rating`; once Rejected, optionally \
                                `POST /api/v1/complaints/{id}/appeal`.

                                ## Typical officer/department workflow
                                `GET /api/v1/complaints?status=ASSIGNED` (scoped automatically to the caller's \
                                assignment/department - see docs/RBAC_MATRIX.md) -> \
                                `PATCH /api/v1/complaints/{id}/status` (multipart, after-photo required for a \
                                Resolved target) -> `POST /api/v1/complaints/{id}/notes` for internal notes.

                                ## Typical AI classification flow (internal, ai-service -> backend)
                                Not called directly by Flutter - `AiClassificationService` calls ai-service's \
                                `POST /api/v1/ai/classify` right after complaint creation, then applies the \
                                result (auto-verify, route to manual review, or flag as a possible duplicate) \
                                before the citizen-facing `GET /api/v1/complaints/{id}` response ever includes \
                                the AI classification fields (`aiClassification.aiStatus`, `.confidence` - \
                                Gap-backlog Patch 30/43).

                                ## Error codes
                                Every non-2xx JSON response uses the same shape: \
                                `{timestamp, status, error, message, path, details}` \
                                (`GlobalExceptionHandler`). Common `error` values: `VALIDATION_ERROR` (400), \
                                `RESOURCE_NOT_FOUND` (404), `INVALID_STATE_TRANSITION` (409), \
                                `DUPLICATE_ACCOUNT` (409), `GRACE_PERIOD_EXPIRED` (409).

                                ## Full role/endpoint authorization matrix
                                See `docs/RBAC_MATRIX.md` in the repository (Gap-backlog Patch 22) for the \
                                complete, code-generated table of which role can call which endpoint.
                                """)
                        .version("v1")
                        .license(new License().name("Internal / Academic Project")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
