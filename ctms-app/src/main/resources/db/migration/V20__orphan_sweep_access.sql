-- V20 — how the orphan sweep touches storage handles without becoming a BYPASSRLS role.
--
-- §16.7: the upload path writes bytes to storage before its transaction commits, so a crash
-- between that write and the commit — or an exception path that itself fails — can leave an
-- object in storage with no metadata row pointing at it. The sweep's question is "does any
-- document, in any trial, under any institution, still reference this storage object?" — a
-- read across the whole table that no session's ordinary RLS scope grants, and rightly so.
--
-- This worker runs on nobody's behalf, exactly like the scan worker in V19: with
-- app.current_user_id unset, documents_scope evaluates false for every row, so a plain SELECT
-- here would silently see nothing and the sweep would conclude every object is an orphan. The
-- same three options from V19 apply, with the same answer: not a BYPASSRLS role, not "run as
-- some user" (there is no user whose identity this operation is performed under), but a
-- narrow SECURITY DEFINER function scoped to exactly the one thing the sweep needs to know.
--
-- The column read is a storage handle, not a clinical field, and the function returns no
-- other column — it grants the ability to answer "is this public id referenced", nothing else.

CREATE FUNCTION app.referenced_storage_public_ids()
RETURNS TABLE (public_id text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT DISTINCT cloudinary_public_id FROM documents;
$$;

GRANT EXECUTE ON FUNCTION app.referenced_storage_public_ids() TO ctms_app;
