-- V6 — row-level security on the structural tables (§7.5, structural class).
--
-- Role names appear in these predicates. That is not a violation of §6.1, which forbids role
-- names in *application* logic: the mapping from role to visible rows is precisely what a
-- policy is for, and keeping it here is what lets the application ask only about permissions.

-- FORCE, not merely ENABLE. ENABLE exempts the table owner, so any code that connected as the
-- owner — one wrong environment variable is enough — would silently see everything. FORCE
-- removes that failure mode. (Superusers are still exempt; that is why the application
-- connects as ctms_app, §7.7.)
ALTER TABLE institutions ENABLE ROW LEVEL SECURITY;
ALTER TABLE institutions FORCE ROW LEVEL SECURITY;
ALTER TABLE trials       ENABLE ROW LEVEL SECURITY;
ALTER TABLE trials       FORCE ROW LEVEL SECURITY;
ALTER TABLE trial_sites  ENABLE ROW LEVEL SECURITY;
ALTER TABLE trial_sites  FORCE ROW LEVEL SECURITY;
ALTER TABLE trial_staff  ENABLE ROW LEVEL SECURITY;
ALTER TABLE trial_staff  FORCE ROW LEVEL SECURITY;

-- Roles that read structural data in full (§7.5). Safety needs cross-trial comparison; the
-- regulator has national oversight; the administrator manages the platform's structure.
CREATE FUNCTION app.reads_all_structure() RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, public
AS $$
    SELECT app.current_role_name() IN
        ('SYSTEM_ADMIN', 'SAFETY_OFFICER', 'REGULATORY_OFFICER');
$$;
GRANT EXECUTE ON FUNCTION app.reads_all_structure() TO ctms_app;

-- ── institutions ─────────────────────────────────────────────────────────────
-- §8.7: names and coordinates are public infrastructure facts, and the map is global (§1.3),
-- so every authenticated session reads them. Anonymous sessions still read nothing, because
-- current_user_id() is NULL and NULL is not TRUE.
CREATE POLICY institutions_read ON institutions FOR SELECT
    USING (app.current_user_id() IS NOT NULL);

CREATE POLICY institutions_write ON institutions FOR ALL
    USING (app.current_role_name() = 'SYSTEM_ADMIN')
    WITH CHECK (app.current_role_name() = 'SYSTEM_ADMIN');

-- ── trials ───────────────────────────────────────────────────────────────────
CREATE POLICY trials_read ON trials FOR SELECT
    USING (app.reads_all_structure() OR app.is_assigned_to_trial(id));

CREATE POLICY trials_insert ON trials FOR INSERT
    WITH CHECK (app.current_role_name() IN ('SYSTEM_ADMIN', 'PRINCIPAL_INVESTIGATOR'));

CREATE POLICY trials_update ON trials FOR UPDATE
    USING (app.current_role_name() = 'SYSTEM_ADMIN' OR app.is_assigned_to_trial(id))
    WITH CHECK (app.current_role_name() = 'SYSTEM_ADMIN' OR app.is_assigned_to_trial(id));

-- ── trial_sites ──────────────────────────────────────────────────────────────
-- is_assigned_to_site treats a NULL site on an assignment as trial-wide (§8.10), so a PI
-- assigned to the trial sees every one of its sites without a row per site.
CREATE POLICY trial_sites_read ON trial_sites FOR SELECT
    USING (app.reads_all_structure() OR app.is_assigned_to_site(trial_id, id));

CREATE POLICY trial_sites_write ON trial_sites FOR ALL
    USING (
        app.current_role_name() = 'SYSTEM_ADMIN'
        OR app.is_assigned_to_trial(trial_id))
    WITH CHECK (
        app.current_role_name() = 'SYSTEM_ADMIN'
        OR app.is_assigned_to_trial(trial_id));

-- ── trial_staff ──────────────────────────────────────────────────────────────
-- The recursion §7.4 warns about would land here: deciding visibility of a trial_staff row
-- requires knowing the caller's assignments, which live in trial_staff. app.active_assignments()
-- is SECURITY DEFINER and so reads the table without re-entering this policy.
CREATE POLICY trial_staff_read ON trial_staff FOR SELECT
    USING (
        app.reads_all_structure()
        OR user_id = app.current_user_id()
        OR app.is_assigned_to_trial(trial_id));

CREATE POLICY trial_staff_write ON trial_staff FOR ALL
    USING (
        app.current_role_name() = 'SYSTEM_ADMIN'
        OR app.is_assigned_to_trial(trial_id))
    WITH CHECK (
        app.current_role_name() = 'SYSTEM_ADMIN'
        OR app.is_assigned_to_trial(trial_id));
