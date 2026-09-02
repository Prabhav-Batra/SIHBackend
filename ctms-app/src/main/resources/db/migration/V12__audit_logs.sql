-- V12 — the audit trail (§8.24, §19).
--
-- Append-only by construction. There is no updated_at and no version column, because the
-- table is never updated: a row records that something happened, and what happened does not
-- change afterwards.

CREATE TABLE audit_logs (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Nullable and ON DELETE SET NULL, never CASCADE. Deleting a user must not erase the
    -- record of what they did — that is precisely the deletion an attacker would want.
    user_id      uuid REFERENCES users (id) ON DELETE SET NULL,
    action       text NOT NULL,
    entity_type  text NOT NULL,
    entity_id    uuid,
    -- PHI-redacted before serialisation (§19.5). The log records what changed, not the
    -- clinical content, so that a table many roles read for compliance never duplicates PHI.
    old_values   jsonb,
    new_values   jsonb,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    ip_address   inet,
    user_agent   text,
    request_id   uuid,
    -- Denormalised so a PI can read their trials' trail without joining through six tables,
    -- and so RLS on this table is a single-column predicate.
    trial_id     uuid REFERENCES trials (id) ON DELETE SET NULL,
    -- Failed and denied attempts are the security-relevant ones; a log of successes only
    -- cannot answer "who tried and was refused".
    outcome      text NOT NULL DEFAULT 'SUCCESS',
    CONSTRAINT ck_audit_logs_outcome CHECK (outcome IN ('SUCCESS','FAILURE','DENIED'))
);

CREATE INDEX ix_audit_logs_entity ON audit_logs (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_user ON audit_logs (user_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_trial ON audit_logs (trial_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_occurred_at ON audit_logs (occurred_at DESC);

-- ── immutability ─────────────────────────────────────────────────────────────
-- Two independent controls, because either alone is insufficient. The revoked grant stops the
-- application role; the trigger stops everyone else, including the owner and anyone who
-- reaches the database with elevated credentials. "Administrators do not edit the audit log"
-- is a policy; a raised exception is a fact.
CREATE FUNCTION reject_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    -- One permitted UPDATE, and only one: the ON DELETE SET NULL referential action that
    -- detaches a deleted user or trial. §8.24 requires those foreign keys to null rather than
    -- cascade, precisely so removing an account cannot erase the record of what it did — but
    -- nulling a foreign key is an UPDATE, so a blanket rejection makes the FK action
    -- impossible and DELETE FROM users fails outright.
    --
    -- Detaching a reference is not an edit to what happened. Every other column must be
    -- byte-identical, which the jsonb comparison enforces, so this cannot be used to alter an
    -- action, an outcome, or a payload.
    IF TG_OP = 'UPDATE'
       -- Every column except the two nullable foreign keys must be byte-identical, so this
       -- cannot be used to alter an action, an outcome, a timestamp or a payload.
       AND (to_jsonb(NEW) - 'user_id' - 'trial_id')
           = (to_jsonb(OLD) - 'user_id' - 'trial_id')
       -- A foreign key may only be detached, never repointed at a different row.
       AND (NEW.user_id IS NULL OR NEW.user_id = OLD.user_id)
       AND (NEW.trial_id IS NULL OR NEW.trial_id = OLD.trial_id)
       -- And something must actually have been detached. Without this an update that changes
       -- nothing at all succeeds on any row whose foreign keys are already null, which is
       -- harmless but makes "audit_logs never accepts UPDATE" untrue as stated.
       AND (OLD.user_id IS DISTINCT FROM NEW.user_id
            OR OLD.trial_id IS DISTINCT FROM NEW.trial_id)
    THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'audit_logs is immutable: % is not permitted', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER tg_audit_logs_immutable
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

-- TRUNCATE bypasses row-level triggers entirely, so it needs its own statement-level guard.
CREATE TRIGGER tg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_mutation();

-- V5's ALTER DEFAULT PRIVILEGES granted SELECT/INSERT/UPDATE on new tables; withdraw the
-- UPDATE here so the application role cannot even attempt a modification.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs FROM ctms_app;
GRANT SELECT, INSERT ON audit_logs TO ctms_app;

-- ── visibility (§8.24) ───────────────────────────────────────────────────────
ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs FORCE  ROW LEVEL SECURITY;

CREATE POLICY audit_logs_read ON audit_logs FOR SELECT
    USING (
        app.current_role_name() IN ('SYSTEM_ADMIN', 'REGULATORY_OFFICER')
        OR (app.current_role_name() = 'PRINCIPAL_INVESTIGATOR'
            AND trial_id IS NOT NULL
            AND app.is_assigned_to_trial(trial_id))
        -- Anyone may see their own actions; §19.3 makes reading *another* user's trail itself
        -- an auditable event.
        OR user_id = app.current_user_id());

-- Writes come from the application on every consequential action, so the insert policy is
-- permissive for any authenticated session. It is the read side that is scoped.
CREATE POLICY audit_logs_write ON audit_logs FOR INSERT
    WITH CHECK (app.current_user_id() IS NOT NULL);
