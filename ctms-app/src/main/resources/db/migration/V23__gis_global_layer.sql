-- V23 — the GIS base map and Level-1 aggregates (§10, §11), which is deliberately global.
--
-- Every authenticated role reaches the same map (§1.3), and §11.3 puts every role at Level 0
-- (base map) and Level 1 (aggregate) regardless of trial assignment — a Research Staff member
-- assigned to one site still "sees the national map as geographic context", and every role
-- reaches aggregate enrolment and compliance percentages nationally.
--
-- trial_sites and trial_compliance cannot answer that directly: their RLS is operational
-- scoping (who may work on this trial), which is a different question from geographic
-- visibility, and applying it here would show a Research Staff member exactly one site on
-- what is supposed to be a national base map — and would show an Ethics Member nothing at
-- all, since ethics_submissions is the only table their RLS currently reaches. This is the
-- same shape of problem V19 and V20 solved for background workers: a legitimate read that
-- ordinary RLS cannot grant without either widening a clinical policy (which would also widen
-- the scoped endpoints — /sites and /compliance — that share that same policy) or building a
-- second, narrow path for exactly the fields this one is for.
--
-- Both functions below are that narrow path. They expose only what §11.2 lists as safe for
-- the map at Level 0/1 — site location and status, structural counts, and enrolment/compliance
-- figures — never a clinical field, never an adverse event, never anything from
-- participant_identities. Level 2/3 drill-down is not built here: it queries trial_sites and
-- trial_compliance directly, under the caller's own RLS, so an out-of-scope site stays
-- genuinely invisible there (§6.4) precisely because that query does not go through either of
-- these functions.

-- The base map's site layer, and the source clustering runs over. No enrolment figure: a
-- single site's raw count on a map every role can open is exactly the re-identification risk
-- §11.4 exists to prevent, aggregate framing or not — enrolment appears suppressed, at the
-- state or city level, in app.gis_area_aggregates below.
CREATE FUNCTION app.gis_site_markers()
RETURNS TABLE (
    site_id           uuid,
    trial_id          uuid,
    site_code         text,
    status            text,
    institution_id    uuid,
    institution_name  text,
    city              text,
    state             text,
    country           text,
    latitude          numeric,
    longitude         numeric
)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT s.id, s.trial_id, s.site_code, s.status,
           i.id, i.name, i.city, i.state, i.country,
           COALESCE(s.latitude, i.latitude), COALESCE(s.longitude, i.longitude)
    FROM trial_sites s
    JOIN institutions i ON i.id = s.institution_id
    WHERE app.current_user_id() IS NOT NULL
      AND COALESCE(s.latitude, i.latitude) IS NOT NULL;
$$;

-- Level-1 aggregates by state or city (§10.5's "district" is not implemented: the schema has
-- no district column — only city and state — and approximating one from city data would
-- misrepresent a real administrative boundary rather than merely simplify it).
--
-- Structural counts (institutions, sites, trials) are never suppressed — organisational
-- information, not participant information (§11.4 rule 2). Enrolment is participant-derived
-- and always routed through app.suppress_small. Compliance status is trial-level regulatory
-- state, not participant data, so it is not a §11.4 concern and is reported as an exact tally.
-- A compliance requirement with no trial_site_id is trial-wide and is counted in every state
-- that trial has a site in, which is the honest reading of "this obligation applies wherever
-- the trial operates" rather than an arbitrary single attribution.
-- Returns the suppression envelope's three fields as plain columns rather than the jsonb
-- app.suppress_small produces. Nothing above the database needs an opinion about which
-- Jackson package this Boot version ships to read a bigint and a boolean — the same reasoning
-- DocumentScanWorker already applies to its job payload.
CREATE FUNCTION app.gis_area_aggregates(group_by text)
RETURNS TABLE (
    area                        text,
    institution_count           bigint,
    site_count                  bigint,
    trial_count                 bigint,
    enrollment_value            bigint,
    enrollment_suppressed       boolean,
    compliance_total            bigint,
    compliance_compliant        bigint,
    compliance_mandatory_open   bigint
)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF app.current_user_id() IS NULL THEN
        RETURN;
    END IF;
    IF group_by NOT IN ('state', 'city') THEN
        RAISE EXCEPTION 'unsupported GIS aggregation level: %', group_by;
    END IF;

    RETURN QUERY
    SELECT
        CASE WHEN group_by = 'state' THEN i.state ELSE i.city END,
        count(DISTINCT i.id)::bigint,
        count(DISTINCT s.id)::bigint,
        count(DISTINCT s.trial_id)::bigint,
        (app.suppress_small(sum(s.current_enrollment)::bigint,
                            sum(s.current_enrollment)::bigint) ->> 'value')::bigint,
        (app.suppress_small(sum(s.current_enrollment)::bigint,
                            sum(s.current_enrollment)::bigint) ->> 'suppressed')::boolean,
        count(DISTINCT tc.id)::bigint,
        count(DISTINCT tc.id) FILTER (WHERE tc.status = 'COMPLIANT')::bigint,
        count(DISTINCT tc.id) FILTER (
            WHERE tc.status IN ('PENDING', 'IN_PROGRESS', 'NON_COMPLIANT')
              AND cr.is_mandatory)::bigint
    FROM trial_sites s
    JOIN institutions i ON i.id = s.institution_id
    LEFT JOIN trial_compliance tc ON tc.trial_id = s.trial_id
    LEFT JOIN compliance_requirements cr ON cr.id = tc.compliance_requirement_id
    GROUP BY CASE WHEN group_by = 'state' THEN i.state ELSE i.city END;
END;
$$;

GRANT EXECUTE ON FUNCTION app.gis_site_markers() TO ctms_app;
GRANT EXECUTE ON FUNCTION app.gis_area_aggregates(text) TO ctms_app;
