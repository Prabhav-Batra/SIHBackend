-- V10 — safety, ethics, compliance and documents (§8.17–§8.23).

-- ── adverse_events ───────────────────────────────────────────────────────────
CREATE TABLE adverse_events (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    participant_id   uuid NOT NULL REFERENCES participants (id) ON DELETE RESTRICT,
    -- Denormalised from the participant so cross-trial safety queries and GIS aggregates
    -- need no join (§28.3). Kept correct by trigger below rather than by the writer.
    trial_id         uuid NOT NULL REFERENCES trials (id) ON DELETE RESTRICT,
    visit_id         uuid REFERENCES visits (id),   -- nullable: events occur between visits
    event_term       text NOT NULL,
    meddra_code      text,
    -- Narrative. Never exposed to GIS or aggregates (§11.2).
    description      text NOT NULL,
    onset_date       date NOT NULL,
    resolution_date  date,
    severity         text NOT NULL,
    seriousness      text NOT NULL DEFAULT 'NON_SERIOUS',
    serious_criteria text[],
    causality        text,
    outcome          text,
    action_taken     text,
    reported_by      uuid NOT NULL REFERENCES users (id),
    reported_at      timestamptz NOT NULL DEFAULT now(),
    status           text NOT NULL DEFAULT 'REPORTED',
    version          integer NOT NULL DEFAULT 1,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_adverse_events_severity CHECK (severity IN ('MILD','MODERATE','SEVERE')),
    CONSTRAINT ck_adverse_events_seriousness CHECK (seriousness IN ('NON_SERIOUS','SERIOUS')),
    CONSTRAINT ck_adverse_events_causality CHECK (causality IS NULL OR causality IN (
        'UNRELATED','UNLIKELY','POSSIBLE','PROBABLE','DEFINITE')),
    CONSTRAINT ck_adverse_events_outcome CHECK (outcome IS NULL OR outcome IN (
        'RECOVERED','RECOVERING','NOT_RECOVERED','RECOVERED_WITH_SEQUELAE','FATAL','UNKNOWN')),
    CONSTRAINT ck_adverse_events_status CHECK (status IN (
        'REPORTED','UNDER_REVIEW','REVIEWED','CLOSED')),
    -- A serious event with no criteria recorded cannot be reported to an authority, which is
    -- the one thing a serious event exists to trigger.
    CONSTRAINT ck_adverse_events_serious_criteria CHECK (
        seriousness <> 'SERIOUS' OR (serious_criteria IS NOT NULL
                                     AND array_length(serious_criteria, 1) > 0)),
    CONSTRAINT ck_adverse_events_date_order CHECK (
        resolution_date IS NULL OR resolution_date >= onset_date)
);
CREATE INDEX ix_adverse_events_trial_seriousness
    ON adverse_events (trial_id, seriousness, reported_at DESC);
CREATE INDEX ix_adverse_events_participant ON adverse_events (participant_id);
CREATE TRIGGER tg_adverse_events_updated_at BEFORE UPDATE ON adverse_events
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- The denormalised trial_id is derived, never trusted from the caller: a mismatch would make
-- the Safety Officer's cross-trial view disagree with the participant's actual trial.
CREATE FUNCTION sync_adverse_event_trial() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    SELECT p.trial_id INTO NEW.trial_id FROM participants p WHERE p.id = NEW.participant_id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tg_adverse_events_sync_trial
    BEFORE INSERT OR UPDATE OF participant_id ON adverse_events
    FOR EACH ROW EXECUTE FUNCTION sync_adverse_event_trial();

-- ── safety_reviews ───────────────────────────────────────────────────────────
CREATE TABLE safety_reviews (
    id                           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    adverse_event_id             uuid NOT NULL REFERENCES adverse_events (id) ON DELETE RESTRICT,
    reviewer_id                  uuid NOT NULL REFERENCES users (id),
    review_date                  timestamptz NOT NULL DEFAULT now(),
    -- May differ from the reported severity; that divergence is itself a signal (§8.18).
    assessed_severity            text NOT NULL,
    assessed_causality           text NOT NULL,
    is_expected                  boolean NOT NULL,
    requires_expedited_reporting boolean NOT NULL DEFAULT false,
    reported_to_authority_at     timestamptz,
    comments                     text,
    decision                     text NOT NULL,
    version                      integer NOT NULL DEFAULT 1,
    created_at                   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_safety_reviews_severity CHECK (assessed_severity IN (
        'MILD','MODERATE','SEVERE')),
    CONSTRAINT ck_safety_reviews_causality CHECK (assessed_causality IN (
        'UNRELATED','UNLIKELY','POSSIBLE','PROBABLE','DEFINITE')),
    CONSTRAINT ck_safety_reviews_decision CHECK (decision IN (
        'ACCEPTED','QUERY_RAISED','ESCALATED','CLOSED'))
);
CREATE INDEX ix_safety_reviews_event ON safety_reviews (adverse_event_id, review_date DESC);

-- ── ethics_submissions ───────────────────────────────────────────────────────
CREATE TABLE ethics_submissions (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trial_id             uuid NOT NULL REFERENCES trials (id) ON DELETE RESTRICT,
    -- The IEC scope key (§5.5). This column alone decides which ethics members see the row.
    institution_id       uuid NOT NULL REFERENCES institutions (id) ON DELETE RESTRICT,
    submission_number    text NOT NULL,
    submission_type      text NOT NULL,
    submitted_by         uuid NOT NULL REFERENCES users (id),
    submitted_at         timestamptz NOT NULL DEFAULT now(),
    protocol_document_id uuid,                    -- FK added with `documents` below
    summary              text NOT NULL,
    status               text NOT NULL DEFAULT 'SUBMITTED',
    decision_date        date,
    approval_valid_until date,
    conditions           text,
    version              integer NOT NULL DEFAULT 1,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_ethics_submissions_number UNIQUE (institution_id, submission_number),
    CONSTRAINT ck_ethics_submissions_type CHECK (submission_type IN (
        'INITIAL','AMENDMENT','CONTINUING_REVIEW','SAE_REPORT','FINAL_REPORT')),
    CONSTRAINT ck_ethics_submissions_status CHECK (status IN (
        'SUBMITTED','UNDER_REVIEW','APPROVED','APPROVED_WITH_CONDITIONS','REJECTED',
        'WITHDRAWN','DEFERRED')),
    -- An approval with conditions that records none is not an auditable decision.
    CONSTRAINT ck_ethics_submissions_conditions CHECK (
        status <> 'APPROVED_WITH_CONDITIONS' OR conditions IS NOT NULL)
);
CREATE INDEX ix_ethics_submissions_institution_status
    ON ethics_submissions (institution_id, status);
CREATE INDEX ix_ethics_submissions_trial ON ethics_submissions (trial_id);
CREATE TRIGGER tg_ethics_submissions_updated_at BEFORE UPDATE ON ethics_submissions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── ethics_reviews ───────────────────────────────────────────────────────────
CREATE TABLE ethics_reviews (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ethics_submission_id uuid NOT NULL REFERENCES ethics_submissions (id) ON DELETE RESTRICT,
    reviewer_id          uuid NOT NULL REFERENCES users (id),
    review_date          timestamptz NOT NULL DEFAULT now(),
    recommendation       text NOT NULL,
    -- Deliberation content. §5.7 keeps this from the regulator, who sees decisions only.
    comments             text NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ethics_reviews_recommendation CHECK (recommendation IN (
        'APPROVE','APPROVE_WITH_CONDITIONS','REJECT','DEFER','REQUEST_CLARIFICATION'))
);
CREATE INDEX ix_ethics_reviews_submission ON ethics_reviews (ethics_submission_id);

-- ── documents ────────────────────────────────────────────────────────────────
CREATE TABLE documents (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Groups every version of one logical document; version 1 sets it to its own id (§17.2).
    document_family_id       uuid NOT NULL,
    trial_id                 uuid REFERENCES trials (id),
    institution_id           uuid REFERENCES institutions (id),
    trial_site_id            uuid REFERENCES trial_sites (id),
    document_type            text NOT NULL,
    title                    text NOT NULL,
    file_name                text NOT NULL,
    mime_type                text NOT NULL,   -- from content sniffing, not the extension
    file_size_bytes          bigint NOT NULL,
    checksum_sha256          text NOT NULL,
    cloudinary_public_id     text NOT NULL,
    cloudinary_resource_type text NOT NULL,
    cloudinary_version       bigint,
    version                  integer NOT NULL DEFAULT 1,
    status                   text NOT NULL DEFAULT 'PENDING_SCAN',
    superseded_by_id         uuid REFERENCES documents (id),
    scan_status              text NOT NULL DEFAULT 'PENDING',
    scanned_at               timestamptz,
    uploaded_by              uuid NOT NULL REFERENCES users (id),
    uploaded_at              timestamptz NOT NULL DEFAULT now(),
    effective_date           date,
    expiry_date              date,
    CONSTRAINT ck_documents_type CHECK (document_type IN (
        'PROTOCOL','CONSENT_FORM','ETHICS_APPROVAL','REGULATORY_SUBMISSION',
        'INVESTIGATOR_BROCHURE','CV','SOURCE_DOCUMENT','SAFETY_REPORT','MONITORING_REPORT',
        'OTHER')),
    CONSTRAINT ck_documents_status CHECK (status IN (
        'PENDING_SCAN','QUARANTINED','DRAFT','CURRENT','SUPERSEDED','WITHDRAWN','ARCHIVED')),
    CONSTRAINT ck_documents_scan_status CHECK (scan_status IN (
        'PENDING','CLEAN','INFECTED','ERROR')),
    CONSTRAINT ck_documents_size CHECK (file_size_bytes > 0 AND file_size_bytes <= 52428800),
    CONSTRAINT ck_documents_resource_type CHECK (cloudinary_resource_type IN (
        'image','raw','video')),
    -- §16.6: a document may only become readable once scanning has cleared it. Encoding this
    -- as a constraint means no code path can publish an unscanned file.
    CONSTRAINT ck_documents_available_requires_clean CHECK (
        status NOT IN ('CURRENT','SUPERSEDED','ARCHIVED') OR scan_status = 'CLEAN')
);
-- §17.2: exactly one CURRENT version per family. Two would make "the current protocol" a
-- question with two answers.
CREATE UNIQUE INDEX uq_documents_one_current_per_family
    ON documents (document_family_id) WHERE status = 'CURRENT';
CREATE INDEX ix_documents_family ON documents (document_family_id);
CREATE INDEX ix_documents_trial_type ON documents (trial_id, document_type);

ALTER TABLE consents ADD CONSTRAINT fk_consents_document
    FOREIGN KEY (consent_document_id) REFERENCES documents (id);
ALTER TABLE ethics_submissions ADD CONSTRAINT fk_ethics_submissions_document
    FOREIGN KEY (protocol_document_id) REFERENCES documents (id);

-- ── compliance ───────────────────────────────────────────────────────────────
CREATE TABLE compliance_requirements (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code             text NOT NULL,
    title            text NOT NULL,
    description      text NOT NULL,
    category         text NOT NULL,
    authority        text,
    applies_to_phase text[],                 -- NULL = all phases
    is_mandatory     boolean NOT NULL DEFAULT true,
    evidence_required boolean NOT NULL DEFAULT true,
    status           text NOT NULL DEFAULT 'ACTIVE',
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_compliance_requirements_code UNIQUE (code),
    CONSTRAINT ck_compliance_requirements_category CHECK (category IN (
        'REGULATORY','ETHICS','SAFETY','DATA_INTEGRITY','SITE_QUALIFICATION','DOCUMENTATION')),
    CONSTRAINT ck_compliance_requirements_status CHECK (status IN ('ACTIVE','SUPERSEDED'))
);

CREATE TABLE trial_compliance (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trial_id                  uuid NOT NULL REFERENCES trials (id) ON DELETE RESTRICT,
    compliance_requirement_id uuid NOT NULL
        REFERENCES compliance_requirements (id) ON DELETE RESTRICT,
    trial_site_id             uuid REFERENCES trial_sites (id),
    status                    text NOT NULL DEFAULT 'PENDING',
    evidence_document_id      uuid REFERENCES documents (id),
    due_date                  date,
    completed_date            date,
    verified_by               uuid REFERENCES users (id),
    verified_at               timestamptz,
    notes                     text,
    version                   integer NOT NULL DEFAULT 1,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_trial_compliance_status CHECK (status IN (
        'PENDING','IN_PROGRESS','COMPLIANT','NON_COMPLIANT','NOT_APPLICABLE','WAIVED')),
    -- Verification is a claim someone made; it must record who and when, together.
    CONSTRAINT ck_trial_compliance_verification CHECK (
        (verified_by IS NULL) = (verified_at IS NULL))
);
CREATE UNIQUE INDEX uq_trial_compliance_scope
    ON trial_compliance (
        trial_id, compliance_requirement_id,
        COALESCE(trial_site_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX ix_trial_compliance_trial_status ON trial_compliance (trial_id, status);
CREATE TRIGGER tg_trial_compliance_updated_at BEFORE UPDATE ON trial_compliance
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
