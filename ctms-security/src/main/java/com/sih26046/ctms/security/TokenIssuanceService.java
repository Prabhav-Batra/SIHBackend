package com.sih26046.ctms.security;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and rotates the token pair (§18.2, §18.6).
 *
 * <p>This exists so the controller does not have to know that an access token must be bound
 * to the session created alongside it. Getting that binding wrong — issuing an access token
 * against the session just revoked by rotation — produces a token that authenticates until it
 * expires and cannot be revoked, which is precisely what {@code sessions} exists to prevent.
 */
@Service
public class TokenIssuanceService {

    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final UserAccountRepository users;
    private final RoleRepository roles;

    public TokenIssuanceService(
            AccessTokenService accessTokens,
            RefreshTokenService refreshTokens,
            UserAccountRepository users,
            RoleRepository roles) {
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.roles = roles;
    }

    @Transactional
    public AuthTokens issueFor(AuthenticatedPrincipal principal) {
        IssuedRefreshToken refresh = refreshTokens.issue(principal.userId(), null, null);
        String access =
                accessTokens.issue(principal.userId(), refresh.sessionId(), principal.roleName());
        return new AuthTokens(
                access, refresh.token(), principal.userId(), principal.roleName());
    }

    /**
     * @throws RefreshTokenReuseException if the token was already exchanged
     * @throws IllegalArgumentException if it is unknown or expired
     */
    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public AuthTokens rotate(String presentedRefreshToken) {
        IssuedRefreshToken rotated = refreshTokens.rotate(presentedRefreshToken, null, null);

        String roleName =
                users.findById(rotated.userId())
                        .flatMap(u -> roles.findById(u.getRoleId()))
                        .map(RoleEntity::getName)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Session has no valid user"));

        String access =
                accessTokens.issue(rotated.userId(), rotated.sessionId(), roleName);
        return new AuthTokens(access, rotated.token(), rotated.userId(), roleName);
    }
}
