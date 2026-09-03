package com.sih26046.ctms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih26046.ctms.web.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The double-submit half of CSRF defence (§18.12) — {@code SameSite} is the other half, already
 * carried entirely by the cookies themselves (§18.3) and needing no code here.
 *
 * <p>Cookie authentication means the browser attaches credentials to a cross-site request
 * automatically; the attacker's page can trigger that, but it cannot <em>read</em> the
 * non-{@code HttpOnly} {@code csrf_token} cookie to construct a matching header, because the
 * same-origin policy stops it. A request without a matching pair is refused before it reaches a
 * controller.
 *
 * <p>Safe methods (GET/HEAD/OPTIONS) never mutate state and are exempt. {@code /auth/login} is
 * exempt because no session — and so no {@code csrf_token} — exists yet at that point; every
 * other mutation, refresh included, must carry the header.
 */
public class CsrfDoubleSubmitFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS =
            Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());

    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private final ObjectMapper mapper;

    public CsrfDoubleSubmitFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (SAFE_METHODS.contains(request.getMethod()) || LOGIN_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String cookieValue = readCookie(request);
        String headerValue = request.getHeader(AuthCookies.CSRF_HEADER);

        if (cookieValue == null || headerValue == null || !cookieValue.equals(headerValue)) {
            reject(response);
            return;
        }

        chain.doFilter(request, response);
    }

    private static String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookies.CSRF_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body =
                ErrorResponse.of("CSRF_TOKEN_MISMATCH", "Missing or invalid CSRF token");
        mapper.writeValue(response.getWriter(), body);
    }
}
