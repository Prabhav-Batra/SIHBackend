package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;

import com.sih26046.ctms.security.RlsUserContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §7.3 — passing identity to PostgreSQL safely.
 *
 * <p>Every RLS policy written from here on reads {@code app.current_user_id}. If it is unset,
 * policies see no user and return nothing; if it leaks between pooled connections, one user
 * reads another's rows. Both failures are silent, which is why they are tested rather than
 * reasoned about.
 */
@SpringBootTest
class RlsIdentityPropagationIT extends AbstractPostgresIT {

    @Autowired TransactionTemplate transactions;

    @Autowired JdbcTemplate jdbc;

    private String guc() {
        return jdbc.queryForObject(
                "SELECT current_setting('app.current_user_id', true)", String.class);
    }

    @Test
    void identityIsVisibleInsideTheTransaction() {
        UUID user = UUID.randomUUID();

        String observed = RlsUserContext.callAs(user, () -> transactions.execute(tx -> guc()));

        assertThat(observed).isEqualTo(user.toString());
    }

    @Test
    void identityIsBlankWhenNoUserIsBound() {
        // Not "the previous user's id". An unauthenticated path must see no identity at all.
        String observed = transactions.execute(tx -> guc());

        assertThat(observed).isNullOrEmpty();
    }

    @Test
    void identityDoesNotOutliveItsTransaction() {
        UUID user = UUID.randomUUID();
        RlsUserContext.callAs(user, () -> transactions.execute(tx -> guc()));

        // set_config(..., is_local => true) ties the value to the transaction, so the next
        // transaction on the same pooled connection starts clean with nothing to reset.
        String next = transactions.execute(tx -> guc());

        assertThat(next).isNullOrEmpty();
    }

    @Test
    void identityNeverLeaksAcrossPooledConnections() {
        // 200 interleaved borrows against a pool far smaller than that, so connections are
        // reused heavily. Each transaction must observe its own identity and no other.
        List<String> mismatches = new ArrayList<>();

        for (int i = 0; i < 200; i++) {
            if (i % 7 == 0) {
                String observed = transactions.execute(tx -> guc());
                if (observed != null && !observed.isEmpty()) {
                    mismatches.add("unauthenticated transaction saw identity: " + observed);
                }
                continue;
            }
            UUID user = UUID.randomUUID();
            String observed = RlsUserContext.callAs(user, () -> transactions.execute(tx -> guc()));
            if (!user.toString().equals(observed)) {
                mismatches.add("expected " + user + " but saw " + observed);
            }
        }

        assertThat(mismatches).isEmpty();
    }

    @Test
    void identityDoesNotSurviveOntoNonTransactionalStatements() {
        // The test that actually distinguishes is_local => true from false.
        //
        // Every transaction sets the GUC explicitly, so a session-scoped value is always
        // overwritten before it can be observed *inside* another transaction — which makes
        // in-transaction assertions blind to the difference. The exposure a session-scoped
        // SET creates is a statement running OUTSIDE Spring's transaction management on a
        // recycled connection: it inherits whatever the last transaction left behind.
        List<String> leaked = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            UUID user = UUID.randomUUID();
            RlsUserContext.callAs(user, () -> transactions.execute(tx -> guc()));

            // No transaction here: JdbcTemplate borrows straight from the pool.
            String observed = guc();
            if (observed != null && !observed.isEmpty()) {
                leaked.add(observed);
            }
        }

        assertThat(leaked)
                .as("a pooled connection carried an identity out of its transaction")
                .isEmpty();
    }

    @Test
    void contextIsClearedAfterTheCallEvenOnFailure() {
        UUID user = UUID.randomUUID();
        try {
            RlsUserContext.callAs(
                    user,
                    () -> {
                        throw new IllegalStateException("boom");
                    });
        } catch (IllegalStateException expected) {
            // A thread returned to the pool still carrying an identity would hand it to the
            // next request served by that thread.
        }

        assertThat(RlsUserContext.current()).isNull();
    }
}
