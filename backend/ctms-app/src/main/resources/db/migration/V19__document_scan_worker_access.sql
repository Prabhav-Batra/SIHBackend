-- V19 — how a background worker touches RLS-protected rows.
--
-- The scan worker runs on nobody's behalf. `app.current_user_id` is unset, so
-- `documents_scope` evaluates its CASE down to `false` and the worker sees no documents at
-- all: its UPDATE matched zero rows and every file stayed PENDING_SCAN forever, silently,
-- because an UPDATE that matches nothing is not an error.
--
-- Three ways out, and the choice matters:
--
--   1. Give workers a BYPASSRLS role. Rejected — this platform is designed so that privilege
--      is never needed, and RlsConnectionGuard refuses to start a connection that holds it.
--   2. Run the scan as the uploading user. Tempting, but `app.current_role_name()` requires
--      status = 'ACTIVE', so deactivating an employee would strand every file they had
--      uploaded in PENDING_SCAN — undownloadable forever, with no error anywhere.
--   3. Narrow SECURITY DEFINER functions for exactly the two operations a scan performs.
--
-- (3), consistent with app.safety_may_read_visit and app.ethics_submission_institution. The
-- privilege is attached to two specific operations rather than to a role that could read
-- everything, and the second function is constrained so that being able to call it is not the
-- same as being able to publish a document.

CREATE FUNCTION app.document_storage_handle(doc uuid)
RETURNS TABLE (public_id text, resource_type text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT cloudinary_public_id, cloudinary_resource_type FROM documents WHERE id = doc;
$$;

-- Records a scan outcome, and nothing else.
--
-- The guards are the point. A SECURITY DEFINER function is a hole through RLS by
-- construction, so this one is written so that holding the right to call it grants only the
-- right to say what a scanner concluded:
--
--   * the scan status must be a scan status;
--   * the only reachable document statuses are DRAFT and QUARANTINED — never CURRENT, so this
--     cannot be used to publish anything as an authoritative version;
--   * it only ever acts on a document still awaiting its scan, so a second call cannot
--     re-open a decided document.
CREATE FUNCTION app.record_scan_result(doc uuid, new_scan_status text, new_status text)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF new_scan_status NOT IN ('CLEAN', 'INFECTED', 'ERROR') THEN
        RAISE EXCEPTION 'not a scan status: %', new_scan_status;
    END IF;

    IF new_status IS NOT NULL AND new_status NOT IN ('DRAFT', 'QUARANTINED') THEN
        RAISE EXCEPTION
            'a scan may move a document only to DRAFT or QUARANTINED, not %', new_status;
    END IF;

    UPDATE documents
    SET scan_status = new_scan_status,
        status      = COALESCE(new_status, status),
        scanned_at  = now()
    WHERE id = doc
      AND status = 'PENDING_SCAN';
END;
$$;

GRANT EXECUTE ON FUNCTION app.document_storage_handle(uuid) TO ctms_app;
GRANT EXECUTE ON FUNCTION app.record_scan_result(uuid, text, text) TO ctms_app;
