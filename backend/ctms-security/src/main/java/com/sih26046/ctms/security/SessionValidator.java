package com.sih26046.ctms.security;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the caller behind an access token (§6.5, §18.8).
 *
 * <p>Permissions are re-resolved from {@code role_permissions} on every request rather than
 * read from the token. A forged or stale {@code role} claim therefore buys nothing, and
 * revoking a grant takes effect on the next request instead of the next login.
 *
 * <p>The session is re-checked too: an access token stays cryptographically valid until it
 * expires, so logout can only work if the server consults its own session state.
 */
@Service
public class SessionValidator {

    private final SessionRepository sessions;
    private final UserAccountRepository users;
    private final RolePermissionService rolePermissions;
    private final RoleRepository roles;
    private final Clock clock;

    public SessionValidator(
            SessionRepository sessions,
            UserAccountRepository users,
            RolePermissionService rolePermissions,
            RoleRepository roles,
            Clock authClock) {
        this.sessions = sessions;
        this.users = users;
        this.rolePermissions = rolePermissions;
        this.roles = roles;
        this.clock = authClock;
    }

    @Transactional(readOnly = true)
    public Optional<CurrentUser> resolve(AccessTokenClaims claims) {
        Optional<SessionEntity> session = sessions.findById(claims.sessionId());
        if (session.isEmpty()
                || session.get().isRevoked()
                || !session.get().getExpiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }

        Optional<UserAccountEntity> user = users.findById(claims.userId());
        if (user.isEmpty() || !user.get().isActive()) {
            return Optional.empty();
        }

        UUID roleId = user.get().getRoleId();
        String roleName = roles.findById(roleId).map(RoleEntity::getName).orElse(null);

        return Optional.of(
                new CurrentUser(
                        user.get().getId(),
                        claims.sessionId(),
                        user.get().getEmail(),
                        roleName,
                        rolePermissions.permissionNamesFor(roleId)));
    }
}
