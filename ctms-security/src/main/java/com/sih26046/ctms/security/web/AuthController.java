package com.sih26046.ctms.security.web;

import com.sih26046.ctms.security.AuthCookies;
import com.sih26046.ctms.security.AuthProperties;
import com.sih26046.ctms.security.AuthTokens;
import com.sih26046.ctms.security.AuthenticatedPrincipal;
import com.sih26046.ctms.security.AuthenticationService;
import com.sih26046.ctms.security.CurrentUser;
import com.sih26046.ctms.security.InvalidCredentialsException;
import com.sih26046.ctms.security.RefreshTokenReuseException;
import com.sih26046.ctms.security.RefreshTokenService;
import com.sih26046.ctms.security.SessionRevocationReason;
import com.sih26046.ctms.security.TokenIssuanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authentication endpoints (§18.5, §18.6, §18.8). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authentication;
    private final TokenIssuanceService tokenIssuance;
    private final RefreshTokenService refreshTokens;
    private final AuthProperties properties;

    public AuthController(
            AuthenticationService authentication,
            TokenIssuanceService tokenIssuance,
            RefreshTokenService refreshTokens,
            AuthProperties properties) {
        this.authentication = authentication;
        this.tokenIssuance = tokenIssuance;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.LoginResponse> login(
            @Valid @RequestBody AuthDtos.LoginRequest request) {

        AuthenticatedPrincipal principal =
                authentication.authenticate(request.email(), request.password());

        AuthTokens tokens = tokenIssuance.issueFor(principal);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie(tokens.accessToken()))
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken()))
                .body(
                        new AuthDtos.LoginResponse(
                                principal.userId(),
                                principal.email(),
                                principal.roleName(),
                                principal.mfaEnabled()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(
            @CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false) String presented) {

        if (presented == null || presented.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        AuthTokens tokens = tokenIssuance.rotate(presented);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie(tokens.accessToken()))
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken()))
                .build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal CurrentUser current) {
        if (current != null) {
            refreshTokens.revoke(current.sessionId(), SessionRevocationReason.LOGOUT);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, AuthCookies.clearAccess().toString())
                .header(HttpHeaders.SET_COOKIE, AuthCookies.clearRefresh().toString())
                .build();
    }

    @GetMapping("/me")
    public AuthDtos.MeResponse me(@AuthenticationPrincipal CurrentUser current) {
        return new AuthDtos.MeResponse(
                current.userId(), current.email(), current.roleName(), current.permissions());
    }

    // ── error mapping ────────────────────────────────────────────────────────
    // §18.5 and §18.17: one generic message, and no internal detail on the wire.

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<AuthDtos.ErrorResponse> onInvalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(
                        AuthDtos.ErrorResponse.of(
                                "INVALID_CREDENTIALS", "Invalid email or password"));
    }

    @ExceptionHandler(RefreshTokenReuseException.class)
    public ResponseEntity<AuthDtos.ErrorResponse> onReuse() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, AuthCookies.clearAccess().toString())
                .header(HttpHeaders.SET_COOKIE, AuthCookies.clearRefresh().toString())
                .body(
                        AuthDtos.ErrorResponse.of(
                                "SESSION_REVOKED", "Please sign in again"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AuthDtos.ErrorResponse> onUnusableToken() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(AuthDtos.ErrorResponse.of("SESSION_INVALID", "Please sign in again"));
    }

    private String accessCookie(String token) {
        return AuthCookies.access(token, properties.accessTokenTtl()).toString();
    }

    private String refreshCookie(String token) {
        return AuthCookies.refresh(token, properties.refreshTokenTtl()).toString();
    }

}
