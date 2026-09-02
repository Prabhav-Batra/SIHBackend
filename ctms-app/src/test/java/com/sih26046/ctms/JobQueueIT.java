package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;

import com.sih26046.ctms.jobs.JobQueue;
import com.sih26046.ctms.jobs.QueuedJob;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spec §10 — the Postgres-backed job queue that replaces Kafka.
 *
 * <p>No free managed broker exists at this project's budget, and at ~10³ concurrency a table
 * drained with {@code SELECT … FOR UPDATE SKIP LOCKED} is not a compromise: it is transactional
 * with the work that enqueues it, so a job cannot be published for a transaction that later
 * rolls back.
 */
@SpringBootTest
class JobQueueIT extends AbstractPostgresIT {

    @Autowired JobQueue jobs;

    @Autowired JdbcTemplate jdbc;

    private final JdbcTemplate owner = ownerJdbc();

    private String statusOf(UUID id) {
        return owner.queryForObject(
                "SELECT status FROM jobs WHERE id = ?::uuid", String.class, id.toString());
    }

    /**
     * A job type nobody else uses.
     *
     * <p>These tests share a database with the rest of the suite, so claiming by a type a real
     * feature also drains means asserting on whichever job happened to be oldest.
     */
    private static String aType() {
        return "TEST_JOB_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void anEnqueuedJobIsPending() {
        UUID id = jobs.enqueue(aType(), "{\"documentId\":\"x\"}");

        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    void claimingReturnsTheJobAndMarksItRunning() {
        String type = aType();
        UUID id = jobs.enqueue(type, "{}");

        Optional<QueuedJob> claimed = jobs.claimNext(type);

        assertThat(claimed).isPresent();
        assertThat(claimed.get().id()).isEqualTo(id);
        assertThat(statusOf(id)).isEqualTo("RUNNING");
    }

    @Test
    void claimingReturnsNothingWhenTheQueueIsEmpty() {
        assertThat(jobs.claimNext("A_TYPE_NOBODY_ENQUEUED")).isEmpty();
    }

    @Test
    void twoWorkersNeverClaimTheSameJob() throws Exception {
        // The property SKIP LOCKED exists for. Without it the second worker blocks on the
        // first's row lock and then claims the same job once it commits — every job processed
        // twice, which for a malware scan or a notification is a visible defect.
        String type = "CONTENDED_" + UUID.randomUUID().toString().substring(0, 8);
        jobs.enqueue(type, "{}");

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Optional<QueuedJob>> claim = () -> jobs.claimNext(type);
            List<Future<Optional<QueuedJob>>> results =
                    pool.invokeAll(List.of(claim, claim));

            long claimed = 0;
            for (Future<Optional<QueuedJob>> r : results) {
                if (r.get().isPresent()) {
                    claimed++;
                }
            }
            assertThat(claimed).isEqualTo(1);
        }
    }

    @Test
    void aFailedJobIsRetriedWithBackoff() {
        String type = aType();
        UUID id = jobs.enqueue(type, "{}");
        jobs.claimNext(type);

        jobs.fail(id, "scanner unreachable");

        assertThat(statusOf(id)).isEqualTo("PENDING");
        Integer attempts =
                owner.queryForObject(
                        "SELECT attempts FROM jobs WHERE id = ?::uuid",
                        Integer.class,
                        id.toString());
        assertThat(attempts).isEqualTo(1);
        // Backoff: it must not be immediately re-claimable, or a failing job spins.
        assertThat(jobs.claimNext(type)).isEmpty();
    }

    @Test
    void aJobThatKeepsFailingIsDeadLettered() {
        String type = "DEADLETTER_" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = jobs.enqueue(type, "{}");

        for (int i = 0; i < 5; i++) {
            owner.update(
                    "UPDATE jobs SET status='RUNNING', run_after = now() WHERE id = ?::uuid",
                    id.toString());
            jobs.fail(id, "still broken");
        }

        // A job retried forever is an outage nobody is paged for; it must come to rest
        // somewhere a human can find it.
        assertThat(statusOf(id)).isEqualTo("DEAD_LETTER");
    }

    @Test
    void completingAJobRemovesItFromTheQueue() {
        String type = aType();
        UUID id = jobs.enqueue(type, "{}");
        jobs.claimNext(type);

        jobs.complete(id);

        assertThat(statusOf(id)).isEqualTo("SUCCEEDED");
    }
}
