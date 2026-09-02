package com.sih26046.ctms.jobs;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Postgres-backed work queue (spec §10).
 *
 * <p>Claiming uses {@code FOR UPDATE SKIP LOCKED}: a worker takes the first due row nobody
 * else holds, rather than blocking on it. Without {@code SKIP LOCKED} a second worker waits on
 * the first worker's lock and then claims the same job once that transaction commits, so every
 * job runs twice — which for a malware scan or a notification is a visible defect rather than
 * a slowdown.
 */
@Service
public class JobQueue {

    /** Base of the exponential backoff: 30s, 60s, 120s, 240s… */
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;

    public JobQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Enqueues work.
     *
     * <p>Joins the caller's transaction on purpose. A job enqueued for work that then rolls
     * back must roll back with it — the classic dual-write bug a broker requires an outbox to
     * avoid, and which a queue in the same database avoids for free.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public UUID enqueue(String jobType, String payloadJson) {
        return jdbc.queryForObject(
                "INSERT INTO jobs (job_type, payload) VALUES (?, ?::jsonb) RETURNING id",
                UUID.class,
                jobType,
                payloadJson);
    }

    /** Claims the oldest due job of a type, or empty when there is none. */
    @Transactional
    public Optional<QueuedJob> claimNext(String jobType) {
        List<QueuedJob> claimed =
                jdbc.query(
                        """
                        UPDATE jobs
                        SET status = 'RUNNING'
                        WHERE id = (
                            SELECT id FROM jobs
                            WHERE job_type = ?
                              AND status = 'PENDING'
                              AND run_after <= now()
                            ORDER BY created_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT 1)
                        RETURNING id, job_type, payload::text, attempts
                        """,
                        (rs, row) ->
                                new QueuedJob(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("job_type"),
                                        rs.getString("payload"),
                                        rs.getInt("attempts")),
                        jobType);
        return claimed.stream().findFirst();
    }

    @Transactional
    public void complete(UUID jobId) {
        jdbc.update("UPDATE jobs SET status = 'SUCCEEDED' WHERE id = ?", jobId);
    }

    /**
     * Records a failure, rescheduling with exponential backoff until the attempt limit, then
     * dead-lettering.
     *
     * <p>A job retried forever is an outage nobody is paged for. Coming to rest in
     * {@code DEAD_LETTER} makes the failure findable.
     */
    @Transactional
    public void fail(UUID jobId, String error) {
        jdbc.update(
                """
                UPDATE jobs
                SET attempts   = attempts + 1,
                    last_error = ?,
                    status     = CASE WHEN attempts + 1 >= max_attempts
                                      THEN 'DEAD_LETTER' ELSE 'PENDING' END,
                    run_after  = now() + (? * power(2, attempts)) * interval '1 second'
                WHERE id = ?
                """,
                error,
                BACKOFF_BASE.toSeconds(),
                jobId);
    }
}
