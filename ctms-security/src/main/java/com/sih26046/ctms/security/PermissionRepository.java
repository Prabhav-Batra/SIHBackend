package com.sih26046.ctms.security;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PermissionRepository extends JpaRepository<PermissionEntity, UUID> {

    /**
     * The role's granted permission names.
     *
     * <p>A native join rather than a mapped association: role_permissions is a pure join
     * table with a composite key and no behaviour, so an entity for it would add mapping
     * ceremony without adding meaning.
     */
    @Query(
            value =
                    """
                    SELECT p.name
                    FROM permissions p
                    JOIN role_permissions rp ON rp.permission_id = p.id
                    WHERE rp.role_id = :roleId
                    """,
            nativeQuery = true)
    List<String> findPermissionNamesByRoleId(@Param("roleId") UUID roleId);
}
