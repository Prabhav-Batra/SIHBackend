package com.sih26046.ctms.security;

import java.time.Clock;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credential verification, lockout, and principal resolution (§18.5).
 *
 * <p>Two properties are load-bearing and easy to lose:
 *
 * <ul>
 *   <li>Every failure raises the same exception. Unknown account, wrong password, inactive
 *       and locked are indistinguishable to the caller, because a differing response is an
 *       account-enumeration oracle.
 *   <li>When no account exists the password is still verified, against a dummy hash. Skipping
 *       the work would make "no such user" measurably faster than "wrong password" and
 *       reintroduce the same oracle through timing.
 * </ul>
 */
@Service
public class AuthenticationService {

    private final UserAccountRepository users;
    private final RoleRepository roles;
    private final RolePermissionService rolePermissions;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String dummyHash;

    public AuthenticationService(
            UserAccountRepository users,
            RoleRepository roles,
            RolePermissionService rolePermissions,
            PasswordEncoder passwordEncoder,
            Clock authClock) {
        this.users = users;
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.passwordEncoder = passwordEncoder;
        this.clock = authClock;
        // Computed once at startup so the absent-account path performs the same Argon2 work
        // as the present-account path.
        this.dummyHash = passwordEncoder.encode("dummy-password-for-constant-time-compare");
    }

    /**
     * Verifies credentials and resolves the caller's permissions.
     *
     * <p>{@code noRollbackFor} is essential rather than cosmetic: the failed-attempt counter
     * and the resulting lockout are written on the path that then throws, and a rollback
     * would discard them — leaving an account that can be brute-forced forever because the
     * count never survives.
     *
     * @throws InvalidCredentialsException for every failure mode, without distinction
     */
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public AuthenticatedPrincipal authenticate(String email, String rawPassword) {
        Optional<UserAccountEntity> found = users.findByEmail(email);

        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyHash);
            throw new InvalidCredentialsException();
        }

        UserAccountEntity user = found.get();

        if (!user.isActive()) {
            // Still verify, so a locked account is not distinguishable by response time.
            passwordEncoder.matches(rawPassword, user.getPasswordHash());
            throw new InvalidCredentialsException();
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            user.recordFailedLogin();
            users.save(user);
            throw new InvalidCredentialsException();
        }

        user.recordSuccessfulLogin(clock.instant());
        users.save(user);

        RoleEntity role =
                roles.findById(user.getRoleId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "User "
                                                        + user.getId()
                                                        + " references a role that does not exist"));

        return new AuthenticatedPrincipal(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                role.getId(),
                role.getName(),
                user.getInstitutionId(),
                rolePermissions.permissionNamesFor(role.getId()),
                user.isMfaEnabled());
    }
}
