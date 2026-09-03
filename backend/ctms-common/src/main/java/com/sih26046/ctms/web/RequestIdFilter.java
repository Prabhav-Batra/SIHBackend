package com.sih26046.ctms.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One id per request, correlating a client-visible error (§18.17) with the server log and the
 * audit trail (§19.6's "full request trace" query, §30.3).
 *
 * <p>Runs before Spring Security — {@code HIGHEST_PRECEDENCE} — so the id exists for a request
 * rejected by authentication itself, not only for ones that reach a controller. Read it during a
 * request via {@link #current()}; it is cleared from MDC in a {@code finally}, because a virtual
 * thread returned to its pool still carrying an id would mislabel the next request it serves.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String incoming = request.getHeader(HEADER);
        String requestId = isPlausible(incoming) ? incoming : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The current request's id, or {@code null} outside a request (a scheduled job, a test). */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    // A caller-supplied id is trusted for correlation but not parsed as anything structured, so
    // this only rules out something absurd (empty, or long enough to be a log-injection attempt)
    // rather than requiring UUID shape.
    private static boolean isPlausible(String value) {
        return value != null && !value.isBlank() && value.length() <= 128;
    }
}
