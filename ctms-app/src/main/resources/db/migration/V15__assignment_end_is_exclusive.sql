-- V15 — an assignment's end_date is the day access ended, not the last day it held.
--
-- app.active_assignments() accepted `end_date >= CURRENT_DATE`, which reads end_date as an
-- inclusive final day. The consequence is that ending an assignment leaves the person with
-- access for the remainder of that day — so revoking someone mid-incident does nothing until
-- midnight, which is precisely when revocation matters most. §8.10 says a past date removes
-- access immediately; treating the date as exclusive is what makes that true.
--
-- The change is strictly more restrictive, so it can only narrow access, never widen it.

CREATE OR REPLACE FUNCTION app.active_assignments()
RETURNS TABLE (trial_id uuid, site_id uuid, staff_role text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT ts.trial_id, ts.trial_site_id, ts.staff_role
    FROM trial_staff ts
    WHERE ts.user_id = app.current_user_id()
      AND ts.start_date <= CURRENT_DATE
      AND (ts.end_date IS NULL OR ts.end_date > CURRENT_DATE);
$$;
