package com.jannetai.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: verifies the Spring context (including all 13 JPA entities,
 * Flyway migration validation, and Hibernate ddl-auto=validate) loads
 * cleanly against a real MySQL instance.
 *
 * NOT VERIFIED in this workspace - no outbound network access and no Maven
 * installation were available here, so this test has not actually been
 * compiled or run (see PHASE_HANDOFF.md TEST RESULTS). Run
 * `mvn -pl backend test` against a real MySQL 8 instance as the first
 * verification step outside this workspace.
 */
@SpringBootTest
@ActiveProfiles("dev")
class BackendApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: a successful context load (including Flyway
        // migrate + Hibernate schema validation against the real database)
        // is the assertion.
    }
}
