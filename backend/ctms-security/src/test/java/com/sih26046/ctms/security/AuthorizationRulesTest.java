package com.sih26046.ctms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * §6.1 — "No role name appears in application logic."
 *
 * <p>Enforced mechanically rather than by review. The rule is easy to state and easy to
 * violate under deadline: {@code hasRole('SYSTEM_ADMIN')} is shorter than working out which
 * permission is actually required, and it silently re-couples authorization to the role
 * table. A build failure is a better guard than a reviewer's memory.
 */
class AuthorizationRulesTest {

    private static final List<String> ROLE_NAMES =
            List.of(
                    "SYSTEM_ADMIN",
                    "PRINCIPAL_INVESTIGATOR",
                    "TRIAL_COORDINATOR",
                    "RESEARCH_STAFF",
                    "ETHICS_MEMBER",
                    "SAFETY_OFFICER",
                    "REGULATORY_OFFICER");

    private final JavaClasses productionClasses =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.sih26046.ctms");

    @Test
    void noAuthorizationExpressionNamesARole() {
        productionClasses.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(PreAuthorize.class))
                .forEach(
                        m -> {
                            String expression =
                                    m.getAnnotationOfType(PreAuthorize.class).value();
                            assertThat(ROLE_NAMES)
                                    .as(
                                            "%s.%s must gate on a permission, not a role: %s",
                                            m.getOwner().getSimpleName(), m.getName(), expression)
                                    .noneMatch(expression::contains);
                        });
    }

    @Test
    void noAuthorizationExpressionUsesRoleBasedHelpers() {
        // hasRole / hasAnyRole are role-based by construction, whatever string follows.
        productionClasses.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(PreAuthorize.class))
                .forEach(
                        m -> {
                            String expression =
                                    m.getAnnotationOfType(PreAuthorize.class)
                                            .value()
                                            .toLowerCase(Locale.ROOT);
                            assertThat(expression)
                                    .as(
                                            "%s.%s must use hasAuthority with a permission",
                                            m.getOwner().getSimpleName(), m.getName())
                                    .doesNotContain("hasrole")
                                    .doesNotContain("hasanyrole");
                        });
    }

    @Test
    void thereIsAtLeastOneGuardedEndpointToCheck() {
        // Guards the guard: if the import ever stops finding annotated methods, the two rules
        // above would pass vacuously and stop protecting anything.
        long guarded =
                productionClasses.stream()
                        .flatMap(c -> c.getMethods().stream())
                        .filter(m -> m.isAnnotatedWith(PreAuthorize.class))
                        .count();
        assertThat(guarded).isPositive();
    }
}
