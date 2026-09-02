-- V13 — the background job queue (spec §10).
--
-- Replaces Kafka, which has no free managed tier at this project's budget. At the concurrency
-- this platform actually sees, a table drained with SELECT … FOR UPDATE SKIP LOCKED is not a
-- compromise: enqueueing is transactional with the work that causes it, so a job can never be
-- published for a transaction that later rolls back — something a broker cannot promise
-- without an outbox.

CREATE TABLE jobs (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type     text NOT NULL,
    payload      jsonb NOT NULL DEFAULT '{}'::jsonb,
    status       text NOT NULL DEFAULT 'PENDING',
    attempts     integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 5,
    -- Claimable only once this passes: the backoff clock. Without it a permanently failing
    -- job spins at the poller's frequency and starves everything behind it.
    run_after    timestamptz NOT NULL DEFAULT now(),
    last_error   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_jobs_status CHECK (status IN (
        'PENDING','RUNNING','SUCCEEDED','FAILED','DEAD_LETTER')),
    CONSTRAINT ck_jobs_attempts CHECK (attempts >= 0 AND attempts <= max_attempts)
);

-- The claim query's index: pending jobs of a type, oldest first, that are due.
CREATE INDEX ix_jobs_claimable ON jobs (job_type, run_after, created_at)
    WHERE status = 'PENDING';
CREATE INDEX ix_jobs_dead_letter ON jobs (created_at DESC) WHERE status = 'DEAD_LETTER';

CREATE TRIGGER tg_jobs_updated_at BEFORE UPDATE ON jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Infrastructure, not domain data: no RLS. Jobs carry no participant rows — a payload is an
-- identifier the handler resolves under the caller's own scope — and the queue is drained by
-- the application itself rather than on behalf of a user, so there is no identity to scope by.
-- This is deliberate and is why the table is absent from the scope harness.
GRANT SELECT, INSERT, UPDATE, DELETE ON jobs TO ctms_app;
