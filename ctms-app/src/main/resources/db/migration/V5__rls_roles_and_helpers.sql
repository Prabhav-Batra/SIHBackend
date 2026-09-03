-- V5 — the database role the application runs as, and the helper functions policies use.
--
-- §7.7: RLS only constrains a role that is neither superuser nor table owner. Migrations run
-- as the owner; the application connects as ctms_app, which owns nothing. Without that split
-- every policy written in V6 would be decoration.

-- ── the application role ─────────────────────────────────────────────────────
-- Created here rather than assumed, so a fresh database is a working database. The password
-- is replaced per environment; on Supabase the role is created once by hand and this block
-- is a no-op.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ctms_app') THEN
        CREATE ROLE ctms_app LOGIN PASSWORD 'cTms_4pp_S3cure!P@ssw0rd2026';
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- Neon does not allow role creation. Cloud deployments run as the single provisioned user.
    NULL;
END
$$;

-- Only grant if the role exists (local dev). Neon uses the single provisioned user.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ctms_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA public TO ctms_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO ctms_app';
        EXECUTE 'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ctms_app';

        -- Tables created by later migrations must be reachable without anyone remembering to grant.
        -- A forgotten grant is a runtime permission error in production, found late.
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
            'GRANT SELECT, INSERT, UPDATE ON TABLES TO ctms_app', current_user);
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
            'GRANT USAGE, SELECT ON SEQUENCES TO ctms_app', current_user);
    END IF;
EXCEPTION WHEN OTHERS THEN
    -- Role creation or grants failed in cloud environment.
    NULL;
END
$$;

-- Note: no DELETE is granted anywhere. §20.1 forbids hard deletes on clinical records, and
-- withholding the privilege is a stronger guarantee than remembering not to write DELETE.

-- ── helper schema ────────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS app;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ctms_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA app TO ctms_app';
    END IF;
EXCEPTION WHEN OTHERS THEN
    NULL;
END
$$;

-- Current session identity, or NULL when unset.
--
-- NULL is the fail-closed pivot of the whole design: every helper below returns NULL, every
-- policy predicate then evaluates to NULL rather than TRUE, and every query returns zero
-- rows. A code path that forgets to bind an identity shows an empty screen, never a leak.
CREATE FUNCTION app.current_user_id() RETURNS uuid
LANGUAGE sql STABLE
SET search_path = pg_catalog, public
AS $$
    SELECT NULLIF(current_setting('app.current_user_id', true), '')::uuid;
$$;

-- SECURITY DEFINER: reads users/trial_staff without triggering their own policies, which is
-- what breaks the recursion described in §7.4 — a policy on trial_staff that needs to read
-- trial_staff to decide visibility.
--
-- search_path is pinned on every SECURITY DEFINER function. Without it a role able to create
-- objects could shadow `users` with its own table and have the elevated function read that
-- instead; this is the standard SECURITY DEFINER escalation.
--
-- STABLE, not VOLATILE, so the planner may evaluate once per statement rather than once per
-- row — the difference between microseconds and an unusable scan.
CREATE FUNCTION app.current_role_name() RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT r.name
    FROM users u
    JOIN roles r ON r.id = u.role_id
    WHERE u.id = app.current_user_id() AND u.status = 'ACTIVE';
$$;

CREATE FUNCTION app.current_institution_id() RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT institution_id
    FROM users
    WHERE id = app.current_user_id() AND status = 'ACTIVE';
$$;

-- The session user's live assignments.
--
-- §8.10 models assignment lifecycle with end_date; the architecture document's sketch of this
-- function referenced a trial_staff.status column that the table specification does not
-- define. end_date is the authoritative form and is what this uses.
CREATE FUNCTION app.active_assignments()
RETURNS TABLE (trial_id uuid, site_id uuid, staff_role text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT ts.trial_id, ts.trial_site_id, ts.staff_role
    FROM trial_staff ts
    WHERE ts.user_id = app.current_user_id()
      AND ts.start_date <= CURRENT_DATE
      AND (ts.end_date IS NULL OR ts.end_date >= CURRENT_DATE);
$$;

-- Convenience predicates, so policies read as intent rather than as joins.
CREATE FUNCTION app.is_assigned_to_trial(target_trial uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (SELECT 1 FROM app.active_assignments() a WHERE a.trial_id = target_trial);
$$;

-- Site scope: an assignment with site_id IS NULL is trial-wide (§8.10), so it covers every
-- site of that trial.
CREATE FUNCTION app.is_assigned_to_site(target_trial uuid, target_site uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM app.active_assignments() a
        WHERE a.trial_id = target_trial
          AND (a.site_id IS NULL OR a.site_id = target_site));
$$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ctms_app') THEN
        EXECUTE 'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA app TO ctms_app';
    END IF;
EXCEPTION WHEN OTHERS THEN
    NULL;
END
$$;
