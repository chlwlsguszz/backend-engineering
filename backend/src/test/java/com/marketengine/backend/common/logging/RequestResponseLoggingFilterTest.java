package com.marketengine.backend.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;

class RequestResponseLoggingFilterTest {

    private static final Duration LONG_THRESHOLD = Duration.ofDays(1);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private Level originalLevel;
    private String originalRequestId;

    @BeforeEach
    void captureLogs() {
        originalRequestId = MDC.get("requestId");
        MDC.remove("requestId");
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void restoreLoggerAndContext() {
        logger.detachAppender(events);
        events.stop();
        logger.setLevel(originalLevel);
        if (originalRequestId == null) {
            MDC.remove("requestId");
        } else {
            MDC.put("requestId", originalRequestId);
        }
    }

    @Test
    void fastSuccessPropagatesClientIdWithoutWritingAnAccessLog() throws Exception {
        MDC.put("requestId", "outer-context");
        request.addHeader("X-Request-Id", "client-id");
        new RequestResponseLoggingFilter(LONG_THRESHOLD).doFilter(request, response, (req, res) ->
                assertThat(MDC.get("requestId")).isEqualTo("client-id"));

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("client-id");
        assertThat(MDC.get("requestId")).isEqualTo("outer-context");
        assertThat(events.list).isEmpty();
    }

    @Test
    void slowSuccessLogsOnceWithGeneratedUuidAndCleansUpMdc() throws Exception {
        new RequestResponseLoggingFilter(Duration.ZERO).doFilter(request, response, (req, res) ->
                assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-Id")));

        String requestId = response.getHeader("X-Request-Id");
        UUID uuid = UUID.fromString(requestId);
        assertThat(uuid.version()).isEqualTo(4);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMDCPropertyMap()).containsEntry("requestId", requestId);
        });
        assertThat(MDC.get("requestId")).isNull();
    }

    @ParameterizedTest
    @CsvSource({"409, WARN", "503, ERROR"})
    void fastErrorResponseIsLoggedOnce(int status, String level) throws Exception {
        new RequestResponseLoggingFilter(LONG_THRESHOLD).doFilter(request, response, (req, res) ->
                response.setStatus(status));

        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.toLevel(level));
            assertThat(event.getFormattedMessage()).contains("status=" + status);
        });
    }

    @Test
    void escapingExceptionIsLoggedOnceAndRethrown() {
        var failure = new ServletException("chain failure");
        MDC.put("requestId", "outer-context");
        assertThatThrownBy(() -> new RequestResponseLoggingFilter(Duration.ZERO)
                .doFilter(request, response, (req, res) -> { throw failure; }))
                .isSameAs(failure);

        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("chain failure");
            assertThat(event.getMDCPropertyMap()).containsEntry("requestId", response.getHeader("X-Request-Id"));
        });
        assertThat(MDC.get("requestId")).isEqualTo("outer-context");
    }

    @Test
    void metricsScrapeBypassesRequestLogging() throws Exception {
        request.setRequestURI("/actuator/prometheus");
        new RequestResponseLoggingFilter(Duration.ZERO).doFilter(request, response, (req, res) ->
                response.setStatus(204));

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader("X-Request-Id")).isNull();
        assertThat(events.list).isEmpty();
    }

    @Test
    void rejectsNegativeSlowRequestThreshold() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RequestResponseLoggingFilter(Duration.ofMillis(-1)));
    }
}
