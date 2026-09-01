package com.sih26046.ctms.security;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Binds the caller's identity to every transaction, for RLS to read (§7.3, spec §6.2).
 *
 * <p>Done in the transaction manager rather than an aspect or a service call because it must
 * be impossible to forget: a query that runs without {@code app.current_user_id} set does not
 * fail loudly, it silently returns nothing — or, if a policy is written permissively, silently
 * returns everything.
 *
 * <p>Two details are load-bearing:
 *
 * <ul>
 *   <li>{@code set_config} rather than {@code SET LOCAL}, because {@code SET LOCAL} accepts no
 *       bind parameters and would force building SQL by concatenation — exactly what §7.3
 *       forbids.
 *   <li>{@code is_local => true}, so the value dies with the transaction. Nothing has to be
 *       reset on return to the pool, and a connection cannot carry one user's identity to the
 *       next borrower. A plain {@code SET} would do precisely that.
 * </ul>
 *
 * <p>This also survives PgBouncer/Supavisor transaction pooling, where the pooling unit and
 * the transaction are the same boundary.
 */
public class RlsAwareTransactionManager extends JpaTransactionManager {

    static final String GUC = "app.current_user_id";

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        EntityManagerHolder holder =
                (EntityManagerHolder)
                        TransactionSynchronizationManager.getResource(getEntityManagerFactory());
        if (holder == null) {
            return;
        }
        applyIdentity(holder.getEntityManager(), RlsUserContext.current());
    }

    private void applyIdentity(EntityManager entityManager, UUID userId) {
        // An absent identity is set explicitly to the empty string rather than skipped, so an
        // unauthenticated transaction can never observe a value left by anything else.
        entityManager
                .createNativeQuery("SELECT set_config(:name, :value, true)")
                .setParameter("name", GUC)
                .setParameter("value", userId == null ? "" : userId.toString())
                .getSingleResult();
    }
}
