-- V16 — grant participant_identity:create to the roles that enrol, and only that.
--
-- V3 granted neither participant_identity permission to any role, reading the §5.8 footnote as
-- covering both. That makes enrolment impossible: §14.6 records who the participant is at the
-- moment they are enrolled, and the WITH CHECK on participant_identities refuses the write.
--
-- The resolution is the distinction §6.3 already draws by having two permissions rather than
-- one. Capturing an identity and re-identifying a participant are different acts:
--
--   participant_identity:create  someone must write down who this person is, once, at
--                                enrolment. Held by the roles that enrol.
--   participant_identity:read    turning a pseudonymised subject code back into a name. The
--                                most sensitive operation on the platform (§8.12), audited
--                                whenever it happens (§19.3), and still granted to nobody.
--
-- So the roles that enrol can write a name they will never afterwards be able to read. The
-- scope harness continues to assert zero visible identity rows for all seven roles, because
-- that assertion is about :read.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name = 'participant_identity:create'
WHERE r.name IN ('PRINCIPAL_INVESTIGATOR', 'TRIAL_COORDINATOR', 'RESEARCH_STAFF')
ON CONFLICT DO NOTHING;
