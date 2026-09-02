package com.sih26046.ctms.security;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The identity that {@link RlsAwareTransactionManager} hands to PostgreSQL (§7.3).
 *
 * <p>Held per thread. Under virtual threads each request gets its own carrier-independent
 * thread, so this stays request-scoped without a request-scoped bean.
 *
 * <p>{@link #callAs} exists so the clear is structural. A {@code set} / {@code clear} pair
 * written by hand is one early return away from leaving an identity on a thread that is
 * about to serve someone else.
 */
public final class RlsUserContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private RlsUserContext() {}

    public static UUID current() {
        return CURRENT.get();
    }

    /** Runs {@code action} with {@code userId} bound, clearing it on every exit path. */
    public static <T> T callAs(UUID userId, Supplier<T> action) {
        UUID previous = CURRENT.get();
        CURRENT.set(userId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * Binds {@code userId} until the returned scope is closed.
     *
     * <p>For callers that cannot be expressed as a lambda — a servlet filter delegating down a
     * chain that throws checked exceptions. Use with try-with-resources so the clear is still
     * structural rather than a {@code finally} someone can forget.
     */
    public static Scope open(UUID userId) {
        UUID previous = CURRENT.get();
        CURRENT.set(userId);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** An active identity binding. Closing restores whatever was bound before. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static void runAs(UUID userId, Runnable action) {
        callAs(
                userId,
                () -> {
                    action.run();
                    return null;
                });
    }
}
