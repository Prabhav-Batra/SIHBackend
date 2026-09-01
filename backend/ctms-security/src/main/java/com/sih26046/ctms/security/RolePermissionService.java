package com.sih26046.ctms.security;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a role's permission set (§6.2).
 *
 * <p>Keyed on role identifier, never role name. §6.1 forbids role names in application
 * logic, and an API that accepted one would make breaking that rule the path of least
 * resistance.
 *
 * <p>This is the read that §6.5 fronts with a cache. The cache is deliberately absent until
 * B8, which profiles before it caches; correctness first, then measurement.
 */
@Service
public class RolePermissionService {

    private final PermissionRepository permissions;

    public RolePermissionService(PermissionRepository permissions) {
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public Set<String> permissionNamesFor(UUID roleId) {
        return Set.copyOf(permissions.findPermissionNamesByRoleId(roleId));
    }
}
