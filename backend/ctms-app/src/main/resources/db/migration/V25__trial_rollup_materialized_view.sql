-- V25 — the dashboard read model (§23, spec §7): "dashboards are the read model, not an
-- optimisation... refreshed CONCURRENTLY by the job runner, dashboards never touch base
-- tables."
--
-- The source query joins adverse_events, trial_compliance and ethics_submissions across
-- every trial nationally — exactly the cross-trial reach RLS on those tables refuses most
-- roles (adverse_events has no REGULATORY_OFFICER branch at all; trial_compliance has none
-- for STAFF/ETHICS/SAFETY). A materialized view has no RLS of its own to lean on anyway
-- (policies attach to tables, not to materialized views), so the source is a SECURITY
-- DEFINER function — same reasoning as V19/V20/V23 — and the view is a pure structural
-- rollup: counts and booleans, never a narrative, never an identity.
--
-- Row-level scope still applies where it matters: nothing queries this view directly. Every
-- caller joins it to `trials`, and `trials`' own RLS decides which rollup rows a given
-- session's join can actually see (§4.1's ordinary mechanism — no new escape here).
--
-- ctms_app owns nothing (§4.2), so it cannot REFRESH a materialized view it did not create;
-- app.refresh_trial_rollup() is the same SECURITY DEFINER answer as the view's own source.
CREATE FUNCTION app.trial_rollup_source()
RETURNS TABLE (
    trial_id                    uuid,
    current_enrollment          int,
    target_enrollment           int,
    site_count                  bigint,
    ae_total                    bigint,
    ae_serious                  bigint,
    ae_unreviewed               bigint,
    compliance_total            bigint,
    compliance_compliant        bigint,
    compliance_mandatory_open   bigint,
    ethics_approved_current     boolean
)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT t.id, t.current_enrollment, t.target_enrollment,
           count(DISTINCT s.id),
           count(DISTINCT ae.id),
           count(DISTINCT ae.id) FILTER (WHERE ae.seriousness = 'SERIOUS'),
           count(DISTINCT ae.id) FILTER (WHERE ae.status IN ('REPORTED', 'UNDER_REVIEW')),
           count(DISTINCT tc.id),
           count(DISTINCT tc.id) FILTER (WHERE tc.status = 'COMPLIANT'),
           count(DISTINCT tc.id) FILTER (
               WHERE tc.status IN ('PENDING', 'IN_PROGRESS', 'NON_COMPLIANT') AND cr.is_mandatory),
           bool_or(
               es.status IN ('APPROVED', 'APPROVED_WITH_CONDITIONS')
               AND (es.approval_valid_until IS NULL OR es.approval_valid_until >= CURRENT_DATE))
    FROM trials t
    LEFT JOIN trial_sites s ON s.trial_id = t.id
    LEFT JOIN adverse_events ae ON ae.trial_id = t.id
    LEFT JOIN trial_compliance tc ON tc.trial_id = t.id
    LEFT JOIN compliance_requirements cr ON cr.id = tc.compliance_requirement_id
    LEFT JOIN ethics_submissions es ON es.trial_id = t.id
    GROUP BY t.id;
$$;

CREATE MATERIALIZED VIEW mv_trial_rollup AS SELECT * FROM app.trial_rollup_source();

-- CONCURRENTLY requires a unique index on the view, and is what lets a refresh run without
-- blocking a dashboard read that arrives mid-refresh.
CREATE UNIQUE INDEX uq_mv_trial_rollup_trial ON mv_trial_rollup (trial_id);

CREATE FUNCTION app.refresh_trial_rollup() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_trial_rollup;
END;
$$;

GRANT SELECT ON mv_trial_rollup TO ctms_app;
GRANT EXECUTE ON FUNCTION app.refresh_trial_rollup() TO ctms_app;
