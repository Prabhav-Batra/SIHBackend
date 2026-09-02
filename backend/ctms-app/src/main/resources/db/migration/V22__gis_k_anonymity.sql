-- V22 — k-anonymity suppression for aggregate GIS figures (§11.4).
--
-- Aggregation alone is not anonymisation when the cells are small: a site with 2 enrolled
-- participants discloses one of two people's status at a named hospital even though no
-- participant-level field ever left the database. This function is the one place that
-- threshold is enforced, applied to every aggregate derived from participant-level rows
-- before the result leaves SQL — never in the API layer, where the true value would still be
-- in memory, in logs, and potentially in a cache entry.
--
-- The numerator is suppressed when the COHORT is small, not merely when the numerator is:
-- reporting "0" for a 2-person site discloses exactly as much as reporting "1". Structural
-- counts (how many sites or trials exist) are never routed through this function at all —
-- they are organisational information, not participant information.
CREATE FUNCTION app.suppress_small(value bigint, cohort_size bigint, k integer DEFAULT 5)
RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN cohort_size IS NULL OR cohort_size = 0 THEN jsonb_build_object('value', 0,    'suppressed', false)
        WHEN cohort_size <  k                       THEN jsonb_build_object('value', null, 'suppressed', true, 'label', '<' || k)
        ELSE                                             jsonb_build_object('value', value,'suppressed', false)
    END;
$$;
