package com.sih26046.ctms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from its access cookie.
 *
 * <p>Permissions become {@link SimpleGrantedAuthority} values named exactly as in the §6.3
 * catalogue, so {@code hasAuthority('participant:create')} in a {@code @PreAuthorize}
 * expression reads as the catalogue does — and no role name ever appears in one (§6.1).
 */
public class AccessTokenAuthFilter extends OncePerRequestFilter {

    private final AccessTokenService accessTokens;
    private final SessionValidator sessionValidator;

    public AccessTokenAuthFilter(
            AccessTokenService accessTokens, SessionValidator sessionValidator) {
        this.accessTokens = accessTokens;
        this.sessionValidator = sessionValidator;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Optional<CurrentUser> current = readCookie(request).flatMap(this::authenticate);

        if (current.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        List<SimpleGrantedAuthority> authorities =
                current.get().permissions().stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(current.get(), null, authorities));

        // Bind the identity that every RLS policy reads (§7.3). Without this the request runs
        // with app.current_user_id unset: policies see no user, reads return nothing and writes
        // are refused. It fails closed, which is the right failure — but it fails silently and
        // completely, so it belongs here in the filter rather than anywhere a handler could
        // omit it.
        try (RlsUserContext.Scope scope = RlsUserContext.open(current.get().userId())) {
            chain.doFilter(request, response);
        }
    }

    private Optional<CurrentUser> authenticate(String token) {
        try {
            return sessionValidator.resolve(accessTokens.verify(token));
        } catch (JwtException e) {
            // Forged, tampered or expired. Anonymous; the entry point answers 401.
            return Optional.empty();
        }
    }

    private Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> AuthCookies.ACCESS_COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }
}
