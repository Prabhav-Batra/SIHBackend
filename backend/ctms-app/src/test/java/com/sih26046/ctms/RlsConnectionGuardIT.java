package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sih26046.ctms.security.RlsConnectionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The production counterpart of {@code TrialScopeRlsIT.applicationConnectsAsANonSuperuserRole}.
 *
 * <p>A test suite proving policies work is worthless if the deployed application connects as a
 * role PostgreSQL exempts from them. That misconfiguration is a single environment variable
 * away, produces no error, and looks identical to a working system right up until it leaks.
 */
@SpringBootTest
class RlsConnectionGuardIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate appJdbc;

    @Autowired RlsConnectionGuard guard;

    @Test
    void acceptsTheApplicationRole() {
        assertThatCode(() -> guard.verify(appJdbc)).doesNotThrowAnyException();
    }

    @Test
    void refusesToRunAsASuperuser() {
        assertThatThrownBy(() -> guard.verify(ownerJdbc()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exempt from row-level security");
    }
}
