-- V24 — telling "no adverse events" from "not visible to you" apart at GIS drill-down.
--
-- The site-detail endpoint (§10.5, Level 2/3) wants an adverse-event count alongside a site's
-- other figures, computed with an ordinary RLS-scoped query so REGULATORY_OFFICER's actual
-- lack of row access is respected, never bypassed. But §6.3's permission catalogue and
-- adverse_events' RLS policy disagree about who that is: REGULATORY_OFFICER holds
-- adverse_event:read (V3) — RBAC's answer to "may this role ever see an event" — while
-- adverse_events_scope (V11) grants that role no rows at all, on purpose, because their
-- aggregate safety view is meant to come from a precomputed rollup (B8), not row access.
--
-- Gating the count query on RBAC's adverse_event:read alone would therefore run the query for
-- a regulator, get zero rows back from RLS, and report "0 adverse events" — indistinguishable
-- from a site where none occurred. That is a false answer, not a private one, and the
-- distinction matters to someone using it for oversight.
--
-- This function is not a new authorization decision; it is adverse_events_scope's own
-- predicate, restated at the trial level so the application can ask "would this query see
-- anything" before running it, and skip the field — not zero it — when the answer is no.
CREATE FUNCTION app.gis_may_see_trial_safety(target_trial uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT app.current_role_name() = 'SAFETY_OFFICER' OR app.is_assigned_to_trial(target_trial);
$$;

GRANT EXECUTE ON FUNCTION app.gis_may_see_trial_safety(uuid) TO ctms_app;
