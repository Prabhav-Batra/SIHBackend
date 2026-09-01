package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;

import com.sih26046.ctms.security.RolePermissionService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * §6.2 — role_permissions is the single place the answer to "who can do X" exists.
 *
 * <p>Role names are resolved to identifiers here in the test, never passed into the service:
 * §6.1 requires that no role name appear in application logic, and an API keyed on role name
 * would be an invitation to break that.
 */
@SpringBootTest
class PermissionResolutionIT extends AbstractPostgresIT {

    @Autowired RolePermissionService rolePermissions;

    @Autowired JdbcTemplate jdbc;

    private UUID roleId(String name) {
        return UUID.fromString(
                jdbc.queryForObject("SELECT id FROM roles WHERE name = ?", String.class, name));
    }

    @Test
    void researchStaffHoldsSiteLevelClinicalEntry() {
        Set<String> permissions = rolePermissions.permissionNamesFor(roleId("RESEARCH_STAFF"));

        assertThat(permissions)
                .contains("observation:create", "visit:create", "participant:read");
    }

    @Test
    void researchStaffCannotDecideEthicsSubmissions() {
        Set<String> permissions = rolePermissions.permissionNamesFor(roleId("RESEARCH_STAFF"));

        assertThat(permissions).doesNotContain("ethics:decide", "ethics:review");
    }

    @Test
    void systemAdminHoldsNoClinicalPermission() {
        // §5.1: platform administration does not imply access to participant data. An admin
        // who could read clinical records would make "administrator" a PHI role.
        Set<String> permissions = rolePermissions.permissionNamesFor(roleId("SYSTEM_ADMIN"));

        assertThat(permissions)
                .doesNotContain(
                        "participant:read",
                        "observation:read",
                        "medication:read",
                        "consent:read",
                        "visit:read");
    }

    @Test
    void regulatoryOfficerHoldsNoSubjectLevelPermission() {
        // ADR-010: oversight operates on aggregates and compliance artefacts, not subject data.
        Set<String> permissions = rolePermissions.permissionNamesFor(roleId("REGULATORY_OFFICER"));

        assertThat(permissions).contains("compliance:read", "regulatory:report");
        assertThat(permissions)
                .doesNotContain("participant:read", "observation:read", "consent:read");
    }

    @Test
    void noRoleHoldsReIdentificationByDefault() {
        // §8.12 and the §5.8 footnote: re-identification is assigned explicitly, never inherited.
        for (String role :
                Set.of(
                        "SYSTEM_ADMIN",
                        "PRINCIPAL_INVESTIGATOR",
                        "TRIAL_COORDINATOR",
                        "RESEARCH_STAFF",
                        "ETHICS_MEMBER",
                        "SAFETY_OFFICER",
                        "REGULATORY_OFFICER")) {
            assertThat(rolePermissions.permissionNamesFor(roleId(role)))
                    .as("%s must not hold participant_identity:read by default", role)
                    .doesNotContain("participant_identity:read");
        }
    }

    @Test
    void anUnknownRoleResolvesToNoPermissions() {
        assertThat(rolePermissions.permissionNamesFor(UUID.randomUUID())).isEmpty();
    }
}
