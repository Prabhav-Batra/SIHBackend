package com.sih26046.ctms.documents;

import com.sih26046.ctms.jobs.JobQueue;
import com.sih26046.ctms.jobs.QueuedJob;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the malware-scan queue (§16.6).
 *
 * <p>Scanning is asynchronous because a synchronous scan would hold an HTTP connection open for
 * seconds on a large file, and because clamd is a separate process that can be down without the
 * upload path needing to care.
 *
 * <p>A worker runs on no user's behalf, so {@code app.current_user_id} is unset and
 * {@code documents_scope} evaluates false for every row — reaching the table directly, through
 * JPA or JDBC alike, finds nothing and updates nothing, without raising an error. So the two
 * things a scan needs go through the narrow SECURITY DEFINER functions added in V19:
 * {@code app.document_storage_handle} to find the bytes, and {@code app.record_scan_result} to
 * record the outcome. The second is constrained in SQL so that being able to call it is not the
 * same as being able to publish a document.
 */
@Component
public class DocumentScanWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentScanWorker.class);

    private final JobQueue jobs;
    private final StorageBackend storage;
    private final MalwareScanner scanner;
    private final JdbcTemplate jdbc;

    public DocumentScanWorker(
            JobQueue jobs, StorageBackend storage, MalwareScanner scanner, JdbcTemplate jdbc) {
        this.jobs = jobs;
        this.storage = storage;
        this.scanner = scanner;
        this.jdbc = jdbc;
    }

    /**
     * Processes at most one job.
     *
     * @return whether there was work to do, so a caller can drain the queue in a loop
     */
    public boolean runOnce() {
        Optional<QueuedJob> claimed = jobs.claimNext(DocumentService.SCAN_JOB);
        if (claimed.isEmpty()) {
            return false;
        }

        QueuedJob job = claimed.get();
        UUID documentId = documentIdOf(job.id());
        if (documentId == null) {
            jobs.complete(job.id());
            log.warn("Scan job {} names no document; discarding", job.id());
            return true;
        }

        try {
            scan(documentId);
            jobs.complete(job.id());
        } catch (Exception e) {
            // A scanner that could not reach a verdict has not said the file is clean. The
            // document stays PENDING_SCAN — undownloadable — and the queue retries with
            // backoff. Only once the attempts are spent is that recorded as a terminal ERROR,
            // so an operator can find the file rather than have it sit silently unscanned.
            jobs.fail(job.id(), e.toString());
            if (job.attempts() + 1 >= maxAttemptsOf(job.id())) {
                updateScan(documentId, DocumentEntity.SCAN_ERROR, null);
                log.error("Scan of document {} failed terminally", documentId, e);
            }
        }
        return true;
    }

    private void scan(UUID documentId) throws Exception {
        StorageHandle handle = handleOf(documentId);
        String publicId = handle.publicId();
        String resourceType = handle.resourceType();

        ScanVerdict verdict;
        try (InputStream content = storage.open(publicId, resourceType)) {
            verdict = scanner.scan(content);
        }

        if (verdict == ScanVerdict.CLEAN) {
            // DRAFT, not CURRENT: passing a scan says the bytes are safe, not that this is the
            // authoritative version of anything (§17.2).
            updateScan(documentId, DocumentEntity.SCAN_CLEAN, DocumentEntity.DRAFT);
        } else {
            updateScan(documentId, DocumentEntity.SCAN_INFECTED, DocumentEntity.QUARANTINED);
            // Quarantine is not a flag on a file we kept. §16.6 deletes the asset, so an
            // infected file cannot be served by any future bug in the download path.
            storage.delete(publicId, resourceType);
            log.warn("Document {} was infected; quarantined and its bytes deleted", documentId);
        }
    }

    @Transactional
    void updateScan(UUID documentId, String scanStatus, String status) {
        // query, not update: a SELECT over a void-returning function still yields a row, and
        // JdbcTemplate.update rejects that with "A result was returned when none was expected"
        // — after the function has already run and committed. The write succeeded and the
        // exception arrived afterwards, which made a broken worker look like a working one.
        jdbc.query(
                "SELECT app.record_scan_result(?, ?, ?)",
                rs -> null,
                documentId,
                scanStatus,
                status);
    }

    private record StorageHandle(String publicId, String resourceType) {}

    private StorageHandle handleOf(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT public_id, resource_type FROM app.document_storage_handle(?)",
                (rs, row) -> new StorageHandle(rs.getString(1), rs.getString(2)),
                documentId);
    }

    private UUID documentIdOf(UUID jobId) {
        // Postgres extracts the field, so the worker needs no JSON library and no opinion
        // about which Jackson package this Boot version ships.
        return jdbc.queryForObject(
                "SELECT (payload->>'documentId')::uuid FROM jobs WHERE id = ?", UUID.class, jobId);
    }

    private int maxAttemptsOf(UUID jobId) {
        Integer max =
                jdbc.queryForObject("SELECT max_attempts FROM jobs WHERE id = ?", Integer.class, jobId);
        return max == null ? 0 : max;
    }
}
