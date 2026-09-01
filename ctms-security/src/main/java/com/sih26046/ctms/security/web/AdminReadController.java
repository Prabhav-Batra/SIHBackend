package com.sih26046.ctms.security.web;

import com.sih26046.ctms.security.PermissionEntity;
import com.sih26046.ctms.security.PermissionRepository;
import com.sih26046.ctms.security.RoleRepository;
import com.sih26046.ctms.security.UserAccountEntity;
import com.sih26046.ctms.security.UserAccountRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read endpoints for the identity tables (§21.2).
 *
 * <p>Every expression names a permission from the §6.3 catalogue. No role name appears in
 * any of them — that is §6.1, and the ArchUnit rule in this module's tests enforces it
 * mechanically rather than by review.
 *
 * <p>Responses are projections, not entities: {@code users.password_hash} must not become
 * reachable because someone later serialises an entity straight to JSON.
 */
@RestController
public class AdminReadController {

    private final UserAccountRepository users;
    private final RoleRepository roles;
    private final PermissionRepository permissions;

    public AdminReadController(
            UserAccountRepository users,
            RoleRepository roles,
            PermissionRepository permissions) {
        this.users = users;
        this.roles = roles;
        this.permissions = permissions;
    }

    public record UserSummary(UUID id, String email, String fullName, UUID roleId) {}

    public record RoleSummary(UUID id, String name, String displayName) {}

    public record PermissionSummary(UUID id, String name, String resource, String action) {}

    @GetMapping("/api/v1/users")
    @PreAuthorize("hasAuthority('user:read')")
    public List<UserSummary> listUsers() {
        return users.findAll().stream()
                .map(this::toSummary)
                .toList();
    }

    @GetMapping("/api/v1/roles")
    @PreAuthorize("hasAuthority('role:read')")
    public List<RoleSummary> listRoles() {
        return roles.findAll().stream()
                .map(r -> new RoleSummary(r.getId(), r.getName(), r.getDisplayName()))
                .toList();
    }

    @GetMapping("/api/v1/permissions")
    @PreAuthorize("hasAuthority('role:read')")
    public List<PermissionSummary> listPermissions() {
        return permissions.findAll().stream().map(this::toSummary).toList();
    }

    private UserSummary toSummary(UserAccountEntity user) {
        return new UserSummary(
                user.getId(), user.getEmail(), user.getFullName(), user.getRoleId());
    }

    private PermissionSummary toSummary(PermissionEntity permission) {
        return new PermissionSummary(
                permission.getId(),
                permission.getName(),
                permission.getResource(),
                permission.getAction());
    }

}
