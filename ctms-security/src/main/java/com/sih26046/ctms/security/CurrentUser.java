package com.sih26046.ctms.security;

import java.util.Set;
import java.util.UUID;

/** The authenticated caller, as resolved per request by {@link AccessTokenAuthFilter}. */
public record CurrentUser(
        UUID userId, UUID sessionId, String email, String roleName, Set<String> permissions) {}
