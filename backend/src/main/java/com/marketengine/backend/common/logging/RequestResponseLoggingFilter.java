package com.marketengine.backend.common.logging;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class RequestResponseLoggingFilter extends OncePerRequestFilter {
    private static final String REQUEST_ID_KEY = "requestId";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final long slowRequestNanos;

    public RequestResponseLoggingFilter(
            @Value("${marketengine.logging.slow-request-threshold:200ms}") Duration slowRequestThreshold) {
        if (slowRequestThreshold.isNegative()) {
            throw new IllegalArgumentException("slow-request-threshold must not be negative");
        }
        slowRequestNanos = slowRequestThreshold.toNanos();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String requestId = resolveRequestId(request);
        String previousRequestId = MDC.get(REQUEST_ID_KEY);
        MDC.put(REQUEST_ID_KEY, requestId);

        try {
            response.setHeader(REQUEST_ID_HEADER, requestId);
            filterChain.doFilter(request, response);
            logResponseIfNeeded(request, response.getStatus(), System.nanoTime() - start);
        } catch (IOException | ServletException | RuntimeException ex) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.error("Request failed method={} uri={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), durationMs, ex);
            throw ex;
        } finally {
            if (previousRequestId == null) {
                MDC.remove(REQUEST_ID_KEY);
            } else {
                MDC.put(REQUEST_ID_KEY, previousRequestId);
            }
        }
    }

    private void logResponseIfNeeded(HttpServletRequest request, int status, long elapsedNanos) {
        if (status < 400 && elapsedNanos < slowRequestNanos) {
            return;
        }
        var event = status >= 500 ? log.atError() : log.atWarn();
        event.log("Request completed method={} uri={} status={} durationMs={}",
                request.getMethod(), request.getRequestURI(), status, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/swagger-ui")
                || uri.equals("/swagger-ui.html")
                || uri.startsWith("/api-docs")
                || uri.equals("/actuator/prometheus");
    }

    private String resolveRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        // Correlation only. Preserve the UUID version 4 and RFC variant bits.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long mostSignificant = (random.nextLong() & ~0xf000L) | 0x4000L;
        long leastSignificant = (random.nextLong() & 0x3fff_ffff_ffff_ffffL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant).toString();
    }
}
