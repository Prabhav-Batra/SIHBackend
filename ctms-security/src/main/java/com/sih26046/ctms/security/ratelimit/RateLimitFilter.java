package com.sih26046.ctms.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih26046.ctms.security.CurrentUser;
import com.sih26046.ctms.web.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Classifies each request into a {@link RateLimitTier} by path and method, then asks the shared
 * {@link RateLimiter} whether this key still has budget (§9.2, §18.10).
 *
 * <p>Runs after {@code AccessTokenAuthFilter} so an authenticated caller is keyed by user id —
 * the correct identity for "60 writes a minute", which an IP address is not (one office, one
 * IP, many staff). The two pre-authentication tiers, {@code LOGIN} and {@code REFRESH}, are keyed
 * by what is available before identity exists: the caller's IP, and the refresh cookie's own
 * value respectively. {@code LOGIN}'s <em>per-email</em> half (§18.10 — "per IP and per email")
 * is enforced separately inside {@code AuthController}, where the email is already a parsed
 * field rather than a request body this filter would have to buffer and replay.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final RateLimiter limiter;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimiter limiter, ObjectMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Classification classification = classify(request);
        if (classification == null) {
            chain.doFilter(request, response);
            return;
        }

        if (!limiter.tryConsume(classification.key(), classification.tier())) {
            reject(response, classification.tier());
            return;
        }

        chain.doFilter(request, response);
    }

    private record Classification(RateLimitTier tier, String key) {}

    private Classification classify(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        if ("POST".equals(method) && "/api/v1/auth/login".equals(path)) {
            return new Classification(RateLimitTier.LOGIN, "ip:" + clientIp(request));
        }
        if ("POST".equals(method) && "/api/v1/auth/refresh".equals(path)) {
            return new Classification(RateLimitTier.REFRESH, "session:" + refreshTokenKey(request));
        }
        if (path.startsWith("/actuator/") || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")) {
            return null;
        }

        String actor = "user:" + callerOr(clientIp(request));

        if ("POST".equals(method)
                && (path.equals("/api/v1/documents") || PATHS.match("/api/v1/documents/*/versions", path))) {
            return new Classification(RateLimitTier.DOCUMENT_UPLOAD, actor);
        }
        if (PATHS.match("/api/v1/gis/sites/*/detail", path)) {
            return new Classification(RateLimitTier.GIS_DRILLDOWN, actor);
        }
        if (path.startsWith("/api/v1/gis/")) {
            return new Classification(RateLimitTier.GIS_READ, actor);
        }
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return new Classification(RateLimitTier.READ, actor);
        }
        return new Classification(RateLimitTier.WRITE, actor);
    }

    private static String callerOr(String fallback) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof CurrentUser user ? user.userId().toString() : fallback;
    }

    private static String refreshTokenKey(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return clientIp(request);
        }
        for (Cookie cookie : cookies) {
            if ("refresh_token".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return clientIp(request);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // The first hop is the original client; Caddy appends, it does not replace (§18.10
            // relies on this being the actual caller, not the proxy in front of the app).
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, RateLimitTier tier) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", Long.toString(tier.periodSeconds()));
        ErrorResponse body =
                ErrorResponse.of("RATE_LIMITED", "Too many requests; slow down and try again");
        mapper.writeValue(response.getWriter(), body);
    }
}
