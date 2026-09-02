-- V11 — policies for the safety, institution-scoped, document and reference classes (§7.5),
-- and the Safety Officer's event-triggered clinical read (§5.6).

-- Does the session user have an adverse event on record for this participant?
--
-- SECURITY DEFINER, because the Safety Officer cannot see the participant row itself (§5.8
-- gives them no access to participants) — so this lookup must not be filtered by the
-- participants policy, or the branch below could never evaluate true.
CREATE FUNCTION app.participant_has_adverse_event(target_participant uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM adverse_events ae WHERE ae.participant_id = target_participant);
$$;

CREATE FUNCTION app.safety_may_read_participant(target_participant uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, public
AS $$
    SELECT app.current_role_name() = 'SAFETY_OFFICER'
       AND app.participant_has_adverse_event(target_participant);
$$;

-- The same question for an observation, resolved through its visit.
--
-- SECURITY DEFINER is essential here and its absence is a subtle trap. Writing this branch
-- inline as `EXISTS (SELECT 1 FROM visits v WHERE v.id = ... )` looks correct but cannot work:
-- `visits` is itself RLS-protected and the Safety Officer has no access to it (§5.8), so the
-- subquery returns no rows and the branch can never evaluate true. A policy that resolves
-- scope through another protected table must do so with the owner's privileges, or it silently
-- denies what it was written to allow.
CREATE FUNCTION app.safety_may_read_visit(target_visit uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT app.current_role_name() = 'SAFETY_OFFICER'
       AND EXISTS (
           SELECT 1
           FROM visits v
           JOIN adverse_events ae ON ae.participant_id = v.participant_id
           WHERE v.id = target_visit);
$$;

-- §5.6 — access follows clinical justification, not role. Before a participant has an event
-- their measurements are invisible to the Safety Officer; afterwards they are available for
-- causality assessment. Deliberately extended to observations and medications only: §5.8
-- gives safety no access to participants, visits or consents even for an event under review.
DROP POLICY observations_scope ON observations;
CREATE POLICY observations_scope ON observations FOR ALL
    USING (app.visit_in_scope(visit_id) OR app.safety_may_read_visit(visit_id))
    WITH CHECK (app.visit_in_scope(visit_id));

DROP POLICY medications_scope ON medications;
CREATE POLICY medications_scope ON medications FOR ALL
    USING (
        app.participant_in_scope(participant_id)
        OR app.safety_may_read_participant(participant_id))
    WITH CHECK (app.participant_in_scope(participant_id));

-- ── safety class ─────────────────────────────────────────────────────────────
-- Assignment scope plus unconditional Safety Officer access: cross-trial comparison is the
-- role's entire purpose, so scoping them to assignments would defeat it.
ALTER TABLE adverse_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE adverse_events FORCE  ROW LEVEL SECURITY;
ALTER TABLE safety_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE safety_reviews FORCE  ROW LEVEL SECURITY;

CREATE POLICY adverse_events_scope ON adverse_events FOR ALL
    USING (
        app.current_role_name() = 'SAFETY_OFFICER'
        OR app.participant_in_scope(participant_id))
    WITH CHECK (
        app.current_role_name() = 'SAFETY_OFFICER'
        OR app.participant_in_scope(participant_id));

-- The regulator is absent here on purpose. §5.7 excludes individual adverse event narratives;
-- their aggregate safety view is served by precomputed rollups (B8), not by row access.
CREATE POLICY safety_reviews_scope ON safety_reviews FOR ALL
    USING (
        app.current_role_name() = 'SAFETY_OFFICER'
        OR EXISTS (
            SELECT 1 FROM adverse_events ae
            WHERE ae.id = safety_reviews.adverse_event_id
              AND app.current_role_name() = 'PRINCIPAL_INVESTIGATOR'
              AND app.is_assigned_to_trial(ae.trial_id)))
    WITH CHECK (app.current_role_name() = 'SAFETY_OFFICER');

-- ── institution-scoped class ─────────────────────────────────────────────────
ALTER TABLE ethics_submissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ethics_submissions FORCE  ROW LEVEL SECURITY;
ALTER TABLE ethics_reviews     ENABLE ROW LEVEL SECURITY;
ALTER TABLE ethics_reviews     FORCE  ROW LEVEL SECURITY;

CREATE POLICY ethics_submissions_scope ON ethics_submissions FOR ALL
    USING (
        -- The reviewing committee, by institution (§5.5).
        (app.current_role_name() = 'ETHICS_MEMBER'
            AND institution_id = app.current_institution_id())
        -- The submitting investigator, for their own trial.
        OR (app.current_role_name() = 'PRINCIPAL_INVESTIGATOR'
            AND app.is_assigned_to_trial(trial_id))
        -- §5.7: the regulator verifies that approvals exist, so reads status and decisions.
        OR app.current_role_name() = 'REGULATORY_OFFICER')
    WITH CHECK (
        app.current_role_name() = 'PRINCIPAL_INVESTIGATOR'
        AND app.is_assigned_to_trial(trial_id));

-- Deliberation content: the committee only. Not the submitting PI, who would otherwise read
-- the reviewers' private assessment of their own trial, and not the regulator (§5.7).
CREATE POLICY ethics_reviews_scope ON ethics_reviews FOR ALL
    USING (
        app.current_role_name() = 'ETHICS_MEMBER'
        AND EXISTS (
            SELECT 1 FROM ethics_submissions es
            WHERE es.id = ethics_reviews.ethics_submission_id
              AND es.institution_id = app.current_institution_id()))
    WITH CHECK (app.current_role_name() = 'ETHICS_MEMBER');

-- ── documents ────────────────────────────────────────────────────────────────
ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE  ROW LEVEL SECURITY;

CREATE FUNCTION app.document_in_scope(
    doc_trial uuid, doc_institution uuid, doc_site uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, public
AS $$
    SELECT CASE app.current_role_name()
        WHEN 'ETHICS_MEMBER' THEN doc_institution = app.current_institution_id()
        WHEN 'SAFETY_OFFICER' THEN doc_trial IS NOT NULL
        WHEN 'REGULATORY_OFFICER' THEN doc_trial IS NOT NULL
        WHEN 'PRINCIPAL_INVESTIGATOR' THEN app.is_assigned_to_trial(doc_trial)
        WHEN 'TRIAL_COORDINATOR' THEN app.is_assigned_to_trial(doc_trial)
        -- A site-level document is site-scoped; a trial-level one is visible to any staff
        -- assigned to that trial.
        WHEN 'RESEARCH_STAFF' THEN
            CASE WHEN doc_site IS NULL THEN app.is_assigned_to_trial(doc_trial)
                 ELSE app.is_assigned_to_site(doc_trial, doc_site) END
        ELSE false
    END;
$$;

CREATE POLICY documents_scope ON documents FOR ALL
    USING (app.document_in_scope(trial_id, institution_id, trial_site_id))
    WITH CHECK (app.document_in_scope(trial_id, institution_id, trial_site_id));

-- ── reference and compliance ─────────────────────────────────────────────────
ALTER TABLE compliance_requirements ENABLE ROW LEVEL SECURITY;
ALTER TABLE compliance_requirements FORCE  ROW LEVEL SECURITY;
ALTER TABLE trial_compliance        ENABLE ROW LEVEL SECURITY;
ALTER TABLE trial_compliance        FORCE  ROW LEVEL SECURITY;

-- Reference class: the requirement catalogue is what every role is measured against, so it is
-- readable by all and writable only by those who define compliance.
CREATE POLICY compliance_requirements_read ON compliance_requirements FOR SELECT
    USING (app.current_user_id() IS NOT NULL);
CREATE POLICY compliance_requirements_write ON compliance_requirements FOR ALL
    USING (app.current_role_name() IN ('SYSTEM_ADMIN','REGULATORY_OFFICER'))
    WITH CHECK (app.current_role_name() IN ('SYSTEM_ADMIN','REGULATORY_OFFICER'));

CREATE POLICY trial_compliance_scope ON trial_compliance FOR ALL
    USING (
        app.current_role_name() IN ('SYSTEM_ADMIN','REGULATORY_OFFICER')
        OR (app.current_role_name() IN ('PRINCIPAL_INVESTIGATOR','TRIAL_COORDINATOR')
            AND app.is_assigned_to_trial(trial_id)))
    WITH CHECK (
        app.current_role_name() IN ('SYSTEM_ADMIN','REGULATORY_OFFICER')
        OR (app.current_role_name() IN ('PRINCIPAL_INVESTIGATOR','TRIAL_COORDINATOR')
            AND app.is_assigned_to_trial(trial_id)));

GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA app TO ctms_app;
