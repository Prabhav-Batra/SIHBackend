package com.sih26046.ctms.security;

import java.util.UUID;

/** The pair of tokens issued by a login or a refresh, plus who they belong to. */
public record AuthTokens(String accessToken, String refreshToken, UUID userId, String roleName) {}
