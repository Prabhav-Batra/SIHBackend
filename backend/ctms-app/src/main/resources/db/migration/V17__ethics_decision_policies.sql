-- V17 — the ethics write path.
--
-- V11 gave ethics_submissions a single FOR ALL policy whose WITH CHECK required the caller to
-- be the assigned investigator. For a SELECT that is harmless, and the scope harness therefore
-- proved the read side correct. But WITH CHECK also governs the new row of an UPDATE, and the
-- committee's decision *is* an UPDATE — so the one role that exists to decide could not record
-- a decision. RBAC grants ETHICS_MEMBER `ethics:decide` (V3); RLS made that permission
-- unusable. This is the V7 → V14 shape again: a policy written for the reading role that
-- silently forbids the writing one.
--
-- Splitting FOR ALL into per-command policies is what makes the difference expressible: who
-- may read, who may submit, and who may decide are three different questions.

DROP POLICY ethics_submissions_scope ON ethics_submissions;

CREATE POLICY ethics_submissions_read ON ethics_submissions FOR SELECT
    USING (
        -- The reviewing committee, by institution (§5.5).
        (app.current_role_name() = 'ETHICS_MEMBER'
            AND institution_id = app.current_institution_id())
        -- The submitting investigator, for their own trial.
        OR (app.current_role_name() = 'PRINCIPAL_INVESTIGATOR'
            AND app.is_assigned_to_trial(trial_id))
        -- §5.7: the regulator verifies that approvals exist, so reads status and decisions.
        OR app.current_role_name() = 'REGULATORY_OFFICER');

CREATE POLICY ethics_submissions_submit ON ethics_submissions FOR INSERT
    WITH CHECK (
        app.current_role_name() = 'PRINCIPAL_INVESTIGATOR'
        AND app.is_assigned_to_trial(trial_id));

-- The decision. Scoped to the committee of the institution that received the submission, so a
-- member cannot decide for another institution's IEC.
CREATE POLICY ethics_submissions_decide ON ethics_submissions FOR UPDATE
    USING (
        app.current_role_name() = 'ETHICS_MEMBER'
        AND institution_id = app.current_institution_id())
    WITH CHECK (
        app.current_role_name() = 'ETHICS_MEMBER'
        AND institution_id = app.current_institution_id());

-- Withdrawal. The investigator may retract their own submission, and this is the only status
-- they may write: the WITH CHECK pins the *destination* rather than the caller, so the same
-- policy that permits withdrawal refuses self-approval. Permissive policies are OR-ed, so
-- without that clause the presence of any PI UPDATE policy would reopen APPROVED to the
-- applicant.
CREATE POLICY ethics_submissions_withdraw ON ethics_submissions FOR UPDATE
    USING (
        app.current_role_name() = 'PRINCIPAL_INVESTIGATOR'
        AND app.is_assigned_to_trial(trial_id))
    WITH CHECK (
        app.current_role_name() = 'PRINCIPAL_INVESTIGATOR'
        AND app.is_assigned_to_trial(trial_id)
        AND status = 'WITHDRAWN');

-- ── ethics_reviews ───────────────────────────────────────────────────────────
--
-- Second hole: V11's WITH CHECK asked only whether the caller was on *a* committee, not
-- whether it was *this* submission's committee. A member elsewhere could therefore write a
-- review onto a submission they cannot read, and the host committee would see it appear.
--
-- The fix needs the submission's institution, and V11 reached it with an inline EXISTS on
-- ethics_submissions. That subquery is itself subject to ethics_submissions' policies (§7.4),
-- which makes the predicate depend on what the caller happens to be able to read — the same
-- trap that made the safety branch on `visits` unreachable. A SECURITY DEFINER lookup asks the
-- structural question directly: which institution owns this submission?
CREATE FUNCTION app.ethics_submission_institution(submission uuid) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT institution_id FROM ethics_submissions WHERE id = submission;
$$;

DROP POLICY ethics_reviews_scope ON ethics_reviews;

CREATE POLICY ethics_reviews_scope ON ethics_reviews FOR ALL
    USING (
        app.current_role_name() = 'ETHICS_MEMBER'
        AND app.ethics_submission_institution(ethics_submission_id)
            = app.current_institution_id())
    WITH CHECK (
        app.current_role_name() = 'ETHICS_MEMBER'
        AND app.ethics_submission_institution(ethics_submission_id)
            = app.current_institution_id());

GRANT EXECUTE ON FUNCTION app.ethics_submission_institution(uuid) TO ctms_app;
