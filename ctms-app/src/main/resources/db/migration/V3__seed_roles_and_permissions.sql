-- V3 — role and permission seed data.
--
-- §6.6: this ships as a migration rather than a runtime bootstrap, so the answer to
-- "who may approve an ethics submission" is version-controlled, reviewable as a diff in a
-- pull request, and identical in every environment.
--
-- The grants below are derived from the §5.8 capability matrix. A matrix letter describes
-- how much a role can *see* (F full, S scoped, A aggregate); RLS narrows S and A at query
-- time (B3). Write permissions follow each role's explicit "Can create / Can update" list
-- in §5, which is narrower than the matrix letter — REGULATORY_OFFICER reads institutions
-- fully but cannot create one.

INSERT INTO roles (id, name, display_name, description) VALUES
 ('00000000-0000-0000-0000-000000000001','SYSTEM_ADMIN','System Administrator','Platform administration: users, roles, institutions, compliance definitions'),
 ('00000000-0000-0000-0000-000000000002','PRINCIPAL_INVESTIGATOR','Principal Investigator','Owns trials and is accountable for their conduct'),
 ('00000000-0000-0000-0000-000000000003','TRIAL_COORDINATOR','Trial Coordinator','Day-to-day operational management of assigned trials'),
 ('00000000-0000-0000-0000-000000000004','RESEARCH_STAFF','Research Staff','Site-level clinical data entry'),
 ('00000000-0000-0000-0000-000000000005','ETHICS_MEMBER','Ethics Committee Member','Institutional ethics review and decisions'),
 ('00000000-0000-0000-0000-000000000006','SAFETY_OFFICER','Safety Officer','Adverse event monitoring and safety review'),
 ('00000000-0000-0000-0000-000000000007','REGULATORY_OFFICER','Regulatory Officer','National oversight via aggregates and compliance artefacts');

-- ── permission catalogue (§6.3) ──────────────────────────────────────────────
-- resource and action are stored separately; ck_permissions_name_shape keeps them
-- consistent with name.
INSERT INTO permissions (name, resource, action)
SELECT r || ':' || a, r, a FROM (VALUES
 ('trial','create'),('trial','read'),('trial','update'),('trial','archive'),
 ('site','create'),('site','read'),('site','update'),
 ('institution','create'),('institution','read'),('institution','update'),
 ('trial_staff','create'),('trial_staff','read'),('trial_staff','delete'),
 ('participant','create'),('participant','read'),('participant','update'),('participant','withdraw'),
 ('participant_identity','read'),('participant_identity','create'),
 ('consent','create'),('consent','read'),('consent','withdraw'),
 ('visit','create'),('visit','read'),('visit','update'),
 ('observation','create'),('observation','read'),('observation','update'),
 ('medication','create'),('medication','read'),('medication','update'),
 ('adverse_event','create'),('adverse_event','read'),('adverse_event','update'),('adverse_event','review'),
 ('safety_report','create'),('safety_report','read'),
 ('ethics','submit'),('ethics','read'),('ethics','review'),('ethics','decide'),
 ('compliance','read'),('compliance','update'),('compliance','define'),
 ('regulatory','report'),
 ('document','upload'),('document','read'),('document','supersede'),('document','archive'),
 ('gis','read'),('gis','drilldown'),
 ('user','create'),('user','read'),('user','update'),('user','deactivate'),
 ('role','read'),('role','assign'),
 ('audit','read')
) AS c(r, a);

-- ── grants ───────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION grant_perms(role_name text, perm_names text[])
RETURNS void LANGUAGE sql AS $$
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    JOIN permissions p ON p.name = ANY(perm_names)
    WHERE r.name = role_name
    ON CONFLICT DO NOTHING;
$$;

-- SYSTEM_ADMIN — platform administration. Deliberately holds NO clinical permission:
-- §5.1 keeps the administrator out of participant and clinical data entirely.
SELECT grant_perms('SYSTEM_ADMIN', ARRAY[
  'user:create','user:read','user:update','user:deactivate',
  'role:read','role:assign',
  'institution:create','institution:read','institution:update',
  'trial:create','trial:read','trial:update','trial:archive',
  'site:create','site:read','site:update',
  'trial_staff:create','trial_staff:read','trial_staff:delete',
  'compliance:read','compliance:update','compliance:define',
  'gis:read','audit:read']);

SELECT grant_perms('PRINCIPAL_INVESTIGATOR', ARRAY[
  'institution:read','trial:create','trial:read','trial:update','trial:archive',
  'site:create','site:read','site:update',
  'trial_staff:create','trial_staff:read','trial_staff:delete',
  'participant:create','participant:read','participant:update','participant:withdraw',
  'consent:create','consent:read','consent:withdraw',
  'visit:create','visit:read','visit:update',
  'observation:create','observation:read','observation:update',
  'medication:create','medication:read','medication:update',
  'adverse_event:create','adverse_event:read','adverse_event:update',
  'safety_report:read','ethics:submit','ethics:read',
  'compliance:read',
  'document:upload','document:read','document:supersede','document:archive',
  'gis:read','gis:drilldown','audit:read']);

SELECT grant_perms('TRIAL_COORDINATOR', ARRAY[
  'institution:read','trial:read','trial:update',
  'site:create','site:read','site:update',
  'trial_staff:create','trial_staff:read','trial_staff:delete',
  'participant:create','participant:read','participant:update','participant:withdraw',
  'consent:create','consent:read','consent:withdraw',
  'visit:create','visit:read','visit:update',
  'observation:create','observation:read','observation:update',
  'medication:create','medication:read','medication:update',
  'adverse_event:create','adverse_event:read','adverse_event:update',
  'compliance:read',
  'document:upload','document:read','document:supersede',
  'gis:read','gis:drilldown']);

SELECT grant_perms('RESEARCH_STAFF', ARRAY[
  'institution:read','trial:read','site:read',
  'participant:create','participant:read','participant:update',
  'visit:create','visit:read','visit:update',
  'observation:create','observation:read','observation:update',
  'medication:create','medication:read','medication:update',
  'adverse_event:create','adverse_event:read',
  'document:upload','document:read',
  'gis:read','gis:drilldown']);

-- ETHICS_MEMBER — no site access (§5.8) and no clinical data at all.
SELECT grant_perms('ETHICS_MEMBER', ARRAY[
  'institution:read','trial:read',
  'ethics:read','ethics:review','ethics:decide',
  'document:read','gis:read']);

-- SAFETY_OFFICER — clinical read is event-triggered: the permission is held, and the RLS
-- policy on observations admits it only for participants with a reported AE (§7.5, §5.6).
SELECT grant_perms('SAFETY_OFFICER', ARRAY[
  'institution:read','trial:read','site:read',
  'observation:read','medication:read',
  'adverse_event:create','adverse_event:read','adverse_event:update','adverse_event:review',
  'safety_report:create','safety_report:read',
  'document:read','gis:read','gis:drilldown']);

-- REGULATORY_OFFICER — ADR-010: aggregates and compliance artefacts, never subject data.
-- No participant, consent, visit, observation or medication permission appears here.
SELECT grant_perms('REGULATORY_OFFICER', ARRAY[
  'institution:read','trial:read','site:read','trial_staff:read',
  'adverse_event:read','safety_report:read','ethics:read',
  'compliance:read','compliance:update','compliance:define','regulatory:report',
  'document:read','gis:read','gis:drilldown','audit:read']);

DROP FUNCTION grant_perms(text, text[]);

-- participant_identity:read and participant_identity:create are intentionally granted to
-- no role here. §8.12 and the §5.8 footnote make re-identification an explicit, separately
-- assigned capability rather than something a role acquires by default.
