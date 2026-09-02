-- V9 — row-level security for the clinical tables (§7.5, site-scoped clinical class).

-- The clinical scope predicate, stated once.
--
-- Note who is absent: SYSTEM_ADMIN and REGULATORY_OFFICER return false for every row, not
-- "scoped access". §5.1 keeps platform administration out of participant data and ADR-010
-- confines oversight to aggregates, so the correct answer for both is nothing at all. A CASE
-- that falls through to false makes that explicit rather than accidental — a new role added
-- later gets no clinical access until someone writes a branch for it.
CREATE FUNCTION app.clinical_scope(target_trial uuid, target_site uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, public
AS $$
    SELECT CASE app.current_role_name()
        WHEN 'RESEARCH_STAFF'         THEN app.is_assigned_to_site(target_trial, target_site)
        WHEN 'PRINCIPAL_INVESTIGATOR' THEN app.is_assigned_to_trial(target_trial)
        WHEN 'TRIAL_COORDINATOR'      THEN app.is_assigned_to_trial(target_trial)
        ELSE false
    END;
$$;

-- SECURITY DEFINER so a policy on `consents` can resolve its participant's site without that
-- lookup being filtered by the policy on `participants` — which would make visibility depend
-- on evaluation order rather than on scope.
CREATE FUNCTION app.participant_in_scope(target_participant uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM participants p
        WHERE p.id = target_participant
          AND app.clinical_scope(p.trial_id, p.trial_site_id));
$$;

CREATE FUNCTION app.visit_in_scope(target_visit uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM visits v
        JOIN participants p ON p.id = v.participant_id
        WHERE v.id = target_visit
          AND app.clinical_scope(p.trial_id, p.trial_site_id));
$$;

-- Whether the session's role holds a named permission (§6.3).
--
-- RBAC normally lives in the application; this exists so the one table where re-identification
-- happens can enforce it in the database too. Both layers must agree before a name is
-- readable (ADR-003).
CREATE FUNCTION app.has_permission(permission_name text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM users u
        JOIN role_permissions rp ON rp.role_id = u.role_id
        JOIN permissions p ON p.id = rp.permission_id
        WHERE u.id = app.current_user_id()
          AND u.status = 'ACTIVE'
          AND p.name = permission_name);
$$;

GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA app TO ctms_app;

-- ── enable ───────────────────────────────────────────────────────────────────
ALTER TABLE participants           ENABLE ROW LEVEL SECURITY;
ALTER TABLE participants           FORCE  ROW LEVEL SECURITY;
ALTER TABLE participant_identities ENABLE ROW LEVEL SECURITY;
ALTER TABLE participant_identities FORCE  ROW LEVEL SECURITY;
ALTER TABLE consents               ENABLE ROW LEVEL SECURITY;
ALTER TABLE consents               FORCE  ROW LEVEL SECURITY;
ALTER TABLE visits                 ENABLE ROW LEVEL SECURITY;
ALTER TABLE visits                 FORCE  ROW LEVEL SECURITY;
ALTER TABLE observations           ENABLE ROW LEVEL SECURITY;
ALTER TABLE observations           FORCE  ROW LEVEL SECURITY;
ALTER TABLE medications            ENABLE ROW LEVEL SECURITY;
ALTER TABLE medications            FORCE  ROW LEVEL SECURITY;

-- ── policies ─────────────────────────────────────────────────────────────────
CREATE POLICY participants_scope ON participants FOR ALL
    USING (app.clinical_scope(trial_id, trial_site_id))
    WITH CHECK (app.clinical_scope(trial_id, trial_site_id));

CREATE POLICY consents_scope ON consents FOR ALL
    USING (app.participant_in_scope(participant_id))
    WITH CHECK (app.participant_in_scope(participant_id));

CREATE POLICY visits_scope ON visits FOR ALL
    USING (app.participant_in_scope(participant_id))
    WITH CHECK (app.participant_in_scope(participant_id));

CREATE POLICY observations_scope ON observations FOR ALL
    USING (app.visit_in_scope(visit_id))
    WITH CHECK (app.visit_in_scope(visit_id));

CREATE POLICY medications_scope ON medications FOR ALL
    USING (app.participant_in_scope(participant_id))
    WITH CHECK (app.participant_in_scope(participant_id));

-- §8.12 — the strictest policy on the platform. Site scope alone is not enough: the session
-- must also hold participant_identity:read, which V3 grants to no role. Re-identification is
-- therefore unreachable by default and becomes possible only by an explicit, auditable grant.
CREATE POLICY participant_identities_scope ON participant_identities FOR ALL
    USING (
        app.has_permission('participant_identity:read')
        AND app.participant_in_scope(participant_id))
    WITH CHECK (
        app.has_permission('participant_identity:create')
        AND app.participant_in_scope(participant_id));
