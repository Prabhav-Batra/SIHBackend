package com.sih26046.ctms.clinical;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Remembers which request produced which participant (§14.5).
 *
 * <p>Keyed on the caller as well as the key, so one client's key cannot return another
 * client's participant.
 *
 * <p><strong>In-process, deliberately.</strong> The safety property here is not held by this
 * class: uq_participants_trial_subject_code already makes a duplicate participant impossible
 * while the client supplies the subject code. Lose this map to a restart and a retry is
 * rejected by that constraint — the caller sees a confusing 422 instead of the original 201,
 * which is a usability defect and not a data-integrity one.
 *
 * <p>This must become a table, written inside the enrolment transaction and keyed
 * {@code UNIQUE (user_id, idempotency_key)}, on either of two triggers:
 *
 * <ul>
 *   <li>a second application instance — two replicas cannot see each other's keys;
 *   <li>server-generated subject codes — the unique constraint stops being a backstop, and
 *       this becomes the only thing between a network timeout and a duplicate participant.
 * </ul>
 *
 * <p>Redis is the wrong home for it either way: idempotency decides whether a second person
 * gets enrolled, which makes it authoritative, and ADR-002 keeps authoritative state out of a
 * cache that fails open on eviction. A table in the same database commits or rolls back with
 * the enrolment it guards — the same argument that made the job queue a table rather than a
 * broker. It should also store a hash of the request body, so reusing a key with different
 * content fails loudly rather than silently returning someone else's participant.
 */
@Component
public class IdempotencyStore {

    /** How long a replay returns the original result rather than enrolling again. */
    static final Duration RETENTION = Duration.ofHours(24);

    private record Entry(UUID participantId, long storedAtMillis) {}

    private final ConcurrentHashMap<String, Entry> seen = new ConcurrentHashMap<>();

    public Optional<UUID> lookup(String key, UUID callerId) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Entry entry = seen.get(compositeKey(key, callerId));
        if (entry == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() - entry.storedAtMillis() > RETENTION.toMillis()) {
            seen.remove(compositeKey(key, callerId));
            return Optional.empty();
        }
        return Optional.of(entry.participantId());
    }

    public void remember(String key, UUID callerId, UUID participantId) {
        if (key == null || key.isBlank()) {
            return;
        }
        seen.put(
                compositeKey(key, callerId),
                new Entry(participantId, System.currentTimeMillis()));
    }

    private static String compositeKey(String key, UUID callerId) {
        return callerId + ":" + key;
    }
}
