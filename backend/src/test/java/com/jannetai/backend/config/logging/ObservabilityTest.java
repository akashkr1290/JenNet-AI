package com.jannetai.backend.config.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jannetai.backend.config.TransientFailures;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit GAP-041: JSON log lines, request ids, MDC propagation, transient-failure rules. NOT EXECUTED here via Maven. */
class ObservabilityTest {

    @Test
    void jsonLogLineIsOneParseableObject() throws Exception {
        String line = JsonLogFormatter.format(Instant.parse("2026-09-26T10:00:00Z"), "ERROR", "jannet-ai-backend",
                "com.x.Y", "main", "abc12345", "multi\nline \"quoted\"", "java.lang.IllegalStateException: x\n\tat a.b");
        assertThat(line).endsWith("\n");
        assertThat(line.trim()).doesNotContain("\n");
        JsonNode json = new ObjectMapper().readTree(line);
        assertThat(json.get("timestamp").asText()).isEqualTo("2026-09-26T10:00:00Z");
        assertThat(json.get("requestId").asText()).isEqualTo("abc12345");
        assertThat(json.get("message").asText()).isEqualTo("multi\nline \"quoted\"");
        assertThat(json.get("stackTrace").asText()).contains("IllegalStateException");
    }

    @Test
    void requestIdFilterAdoptsValidIdsEchoesThemAndCleansUp() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(MDC.get(RequestIds.MDC_KEY));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/wards");
        request.addHeader(RequestIds.HEADER, "0123456789abcdef0123456789abcdef");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(seen.get()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo(seen.get());
        assertThat(MDC.get(RequestIds.MDC_KEY)).isNull();

        MockHttpServletRequest forged = new MockHttpServletRequest("GET", "/api/v1/wards");
        forged.addHeader(RequestIds.HEADER, "evil\nINJECTED log line");
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(forged, second, chain);
        assertThat(second.getHeader(RequestIds.HEADER)).hasSize(32).doesNotContain("\n");
    }

    @Test
    void mdcIsCarriedToExecutorThreads() throws Exception {
        MDC.put(RequestIds.MDC_KEY, "req-abcdefgh");
        Runnable decorated;
        AtomicReference<String> seen = new AtomicReference<>();
        try {
            decorated = new MdcTaskDecorator().decorate(() -> seen.set(MDC.get(RequestIds.MDC_KEY)));
        } finally {
            MDC.clear();
        }
        Thread t = new Thread(decorated);
        t.start();
        t.join();
        assertThat(seen.get()).isEqualTo("req-abcdefgh");
    }

    @Test
    void onlyConnectionFailuresAreTransient() {
        assertThat(TransientFailures.isTransient(new CannotCreateTransactionException("x",
                new SQLTransientConnectionException("Connection is not available, request timed out")))).isTrue();
        assertThat(TransientFailures.isTransient(new CannotCreateTransactionException("x",
                new java.sql.SQLIntegrityConstraintViolationException("dup")))).isFalse();
        assertThat(TransientFailures.backoffMillis(200, 1)).isEqualTo(200);
        assertThat(TransientFailures.backoffMillis(200, 3)).isEqualTo(800);
    }
}
