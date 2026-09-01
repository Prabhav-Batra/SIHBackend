-- V7 — narrow the trial_staff policies to the §5.8 capability matrix.
--
-- V6 reused app.reads_all_structure() for this table and allowed any assigned user to read
-- its rows. Both were too broad. §5.8 gives trial_staff to SYSTEM_ADMIN (full),
-- REGULATORY_OFFICER (full), and PRINCIPAL_INVESTIGATOR / TRIAL_COORDINATOR (scoped to their
-- assignments) — and to nobody else. Notably SAFETY_OFFICER, which reads trials and sites in
-- full, has no access to staffing records: its remit is clinical safety, not who staffs a site.
--
-- Caught by the scope harness rather than by review. The RBAC layer already withholds
-- trial_staff:read from those roles, so the API would have returned 403 regardless; this is
-- the second layer of ADR-003 being made to agree with the first.

DROP POLICY trial_staff_read ON trial_staff;
DROP POLICY trial_staff_write ON trial_staff;

CREATE POLICY trial_staff_read ON trial_staff FOR SELECT
    USING (
        app.current_role_name() IN ('SYSTEM_ADMIN', 'REGULATORY_OFFICER')
        OR (
            app.current_role_name() IN ('PRINCIPAL_INVESTIGATOR', 'TRIAL_COORDINATOR')
            AND app.is_assigned_to_trial(trial_id)));

-- The regulator reads staffing but does not staff trials (§5.7).
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
            AND app.is_assigned_to_trial(trial_id)));
