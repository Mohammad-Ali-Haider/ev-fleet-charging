package com.moveinsync.evfleet.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs on every HTTP request: request id, size limit, status metrics and a log line. */
@Component
public class RequestObservability extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestObservability.class);

    private final MeterRegistry metrics;

    /** Receives the metrics registry. */
    public RequestObservability(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    /** Wraps one request: rejects bodies over 16 MB, then counts and logs the response. */
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", id);
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (request.getContentLengthLong() > 16 * 1024 * 1024) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"REQUEST_TOO_LARGE\"}}");

            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            metrics.counter("evfleet.http.responses", "status", String.valueOf(response.getStatus())).increment();
            LOG.atInfo().addKeyValue("event", "HTTP_REQUEST").addKeyValue("requestId", id).addKeyValue("status", response.getStatus()).log(
                "HTTP request handled");
        }
    }
}
