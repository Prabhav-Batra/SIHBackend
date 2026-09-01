package com.sih26046.ctms.security.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import java.util.UUID;

/** Request and response bodies for /api/v1/auth. */
public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank @Email String email, @NotBlank String password) {}

    /** §18.5 — mfaRequired is surfaced but MFA itself is not implemented in the MVP (§18.7). */
    public record LoginResponse(UUID userId, String email, String role, boolean mfaRequired) {}

    public record MeResponse(
            UUID userId, String email, String role, Set<String> permissions) {}

    public record ErrorResponse(ErrorBody error) {
        public static ErrorResponse of(String code, String message) {
            return new ErrorResponse(new ErrorBody(code, message));
        }
    }

    public record ErrorBody(String code, String message) {}
}
