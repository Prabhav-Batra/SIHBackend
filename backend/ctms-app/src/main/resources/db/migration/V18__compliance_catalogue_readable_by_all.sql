-- V18 — reconcile the compliance catalogue's two access layers.
--
-- V11 declares the requirement catalogue reference data:
--
--     CREATE POLICY compliance_requirements_read ON compliance_requirements FOR SELECT
--         USING (app.current_user_id() IS NOT NULL);
--
-- "readable by every authenticated session", and the scope harness asserts exactly that — one
-- visible row for all seven roles. But V3 granted `compliance:read` to four roles only, so at
-- the API the ethics committee and the safety officer were refused a catalogue the database
-- was already handing them. The row-level layer and the permission layer disagreed, and
-- because each has its own test, both halves passed while the endpoint did not work.
--
-- The policy is the one that matches the intent. A committee reviews a protocol against the
-- regulatory requirements it must satisfy, and a safety officer's expedited-reporting duties
-- are themselves compliance requirements; neither can work against a catalogue they cannot
-- read. Nothing here grants `compliance:update` or `compliance:define` — reading what the
-- rules are and deciding them remain separate (§5.8).
--
-- V3's grant_perms helper is dropped at the end of that migration, so this inserts directly.

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name IN ('ETHICS_MEMBER', 'SAFETY_OFFICER')
  AND p.name = 'compliance:read'
ON CONFLICT DO NOTHING;
