-- V14 — let a trial's creator staff it.
--
-- V7 gated trial_staff writes on app.is_assigned_to_trial(trial_id), which is unsatisfiable
-- for the first assignment on a new trial: you must already be assigned in order to create the
-- assignment. Only SYSTEM_ADMIN could ever staff a trial, and a principal investigator could
-- not read back the trial they had just created — the read policy resolves through the same
-- table.
--
-- The narrow fix is to let the creator, and only the creator, make that first assignment.

-- SECURITY DEFINER: at this moment the investigator cannot yet see the trial row through
-- trials_read, because that also resolves through trial_staff. The check has to read the row
-- the caller is not yet permitted to select.
CREATE FUNCTION app.is_trial_creator(target_trial uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM trials t
        WHERE t.id = target_trial
          AND t.created_by = app.current_user_id());
$$;
GRANT EXECUTE ON FUNCTION app.is_trial_creator(uuid) TO ctms_app;

DROP POLICY trial_staff_write ON trial_staff;

CREATE POLICY trial_staff_write ON trial_staff FOR ALL
    USING (
        app.current_role_name() = 'SYSTEM_ADMIN'
        OR (
            app.current_role_name() IN ('PRINCIPAL_INVESTIGATOR', 'TRIAL_COORDINATOR')
            AND app.is_assigned_to_trial(trial_id)))
    WITH CHECK (
        app.current_role_name() = 'SYSTEM_ADMIN'
        OR (
            app.current_role_name() IN ('PRINCIPAL_INVESTIGATOR', 'TRIAL_COORDINATOR')
            AND app.is_assigned_to_trial(trial_id))
        -- The bootstrap. Deliberately creator-only and deliberately on WITH CHECK alone: it
        -- permits writing the first assignment, not reading anyone else's.
        OR (
            app.current_role_name() = 'PRINCIPAL_INVESTIGATOR'
            AND app.is_trial_creator(trial_id)));
