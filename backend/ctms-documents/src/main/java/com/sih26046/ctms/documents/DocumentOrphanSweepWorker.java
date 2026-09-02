package com.sih26046.ctms.documents;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Finds and removes storage objects that nothing in {@code documents} points at (§16.7).
 *
 * <p>The upload path writes bytes to storage <em>before</em> its transaction commits — §16.7's
 * own argument for that ordering is that the alternative, committing metadata first, produces a
 * document row that 404s, which is worse because the platform believes it holds a file it does
 * not. The upload handler's exception path already deletes the object it just wrote when the
 * transaction that would have recorded it fails; this sweep catches what a bare crash between
 * the write and the commit skips, and it is the second, independent mitigation §16.7 names.
 *
 * <p>Two properties keep this from being able to delete something in use. First, comparison is
 * against every row in {@code documents} regardless of trial, institution, or status — a
 * superseded or quarantined document's handle is still "referenced" even though its own bytes
 * may already be gone, so nothing about a document's lifecycle can make this sweep touch a
 * different document's object. Second, the {@link DocumentProperties.OrphanSweep#minAge()}
 * delay (24 hours by default) means an object less than a transaction's lifetime old is never a
 * candidate, however briefly unreferenced it looks — an in-flight upload is not a race this
 * sweep can lose.
 *
 * <p>This worker runs on nobody's behalf, exactly like {@link DocumentScanWorker}. With {@code
 * app.current_user_id} unset, {@code documents_scope} evaluates false for every row, so a plain
 * query would see no rows and conclude every object is an orphan. {@code
 * app.referenced_storage_public_ids()} (V20) is the narrow SECURITY DEFINER read that answers
 * the one question this sweep needs, in place of a BYPASSRLS role.
 */
@Component
public class DocumentOrphanSweepWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentOrphanSweepWorker.class);

    private final StorageBackend storage;
    private final JdbcTemplate jdbc;
    private final Duration minAge;

    public DocumentOrphanSweepWorker(
            StorageBackend storage, JdbcTemplate jdbc, DocumentProperties properties) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.minAge = properties.orphanSweep().minAge();
    }

    /**
     * Sweeps once.
     *
     * @return how many objects were removed
     */
    public int sweep() {
        List<StoredAsset> stored;
        try {
            stored = storage.list();
        } catch (IOException e) {
            // A listing that failed says nothing about which objects are orphaned — it is not
            // evidence of anything. Treating a partial or failed listing as complete would risk
            // deleting objects the failure simply did not return; skipping the run risks
            // nothing but a day's delay, and the next run tries again.
            log.error("Orphan sweep could not list storage objects; skipping this run", e);
            return 0;
        }

        if (stored.isEmpty()) {
            return 0;
        }

        Set<String> referenced = referencedPublicIds();
        Instant cutoff = Instant.now().minus(minAge);

        int removed = 0;
        for (StoredAsset asset : stored) {
            if (referenced.contains(asset.publicId())) {
                continue;
            }
            if (asset.storedAt().isAfter(cutoff)) {
                continue;
            }
            storage.delete(asset.publicId(), asset.resourceType());
            log.warn(
                    "Orphan sweep removed unreferenced storage object {} ({}), stored at {}",
                    asset.publicId(),
                    asset.resourceType(),
                    asset.storedAt());
            removed++;
        }
        return removed;
    }

    private Set<String> referencedPublicIds() {
        Set<String> ids = new HashSet<>();
        jdbc.query(
                "SELECT public_id FROM app.referenced_storage_public_ids()",
                rs -> {
                    ids.add(rs.getString(1));
                });
        return ids;
    }
}
