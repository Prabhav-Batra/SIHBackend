package com.sih26046.ctms.security;

import java.util.Set;
import java.util.UUID;

/**
 * A successfully authenticated user and their resolved capabilities.
 *
 * @param roleName carried for the token's {@code role} claim, which renders navigation. It is
 *     never the authorization source — every check consults {@code permissions} (§18.2).
 */
public record AuthenticatedPrincipal(
        UUID userId,
        String email,
        String fullName,
        UUID roleId,
        String roleName,
        UUID institutionId,
        Set<String> permissions,
        boolean mfaEnabled) {}
