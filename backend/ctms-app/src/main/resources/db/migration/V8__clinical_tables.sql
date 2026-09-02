-- V8 — the clinical record (§8.11–§8.16).
--
-- Everything here is site-scoped clinical data. A policy mistake on these tables exposes PHI,
-- not a staffing list, which is why every one of them is registered in the scope harness.

-- ── participants ─────────────────────────────────────────────────────────────
-- Pseudonymised by construction: no name, no full date of birth, no contact details. Those
-- live in participant_identities and nowhere else (ADR-011).
CREATE TABLE participants (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trial_id           uuid NOT NULL REFERENCES trials (id) ON DELETE RESTRICT,
    -- Not nullable: every participant belongs to exactly one site, and site-scoped RLS has
    -- nothing to resolve against if this is absent.
    trial_site_id      uuid NOT NULL REFERENCES trial_sites (id) ON DELETE RESTRICT,
    subject_code       text NOT NULL,
    screening_number   text,
    enrollment_date    date NOT NULL,
    randomization_arm  text,
    status             text NOT NULL DEFAULT 'SCREENING',
    withdrawal_date    date,
    withdrawal_reason  text,
    -- Year only. Age stratification without a re-identifying full date of birth (§8.11).
    date_of_birth_year integer,
    sex                text,
    version            integer NOT NULL DEFAULT 1,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    created_by         uuid REFERENCES users (id),
    updated_by         uuid REFERENCES users (id),
    CONSTRAINT uq_participants_trial_subject_code UNIQUE (trial_id, subject_code),
    CONSTRAINT ck_participants_status CHECK (status IN (
        'SCREENING','ENROLLED','ACTIVE','COMPLETED','WITHDRAWN','LOST_TO_FOLLOWUP',
        'SCREEN_FAILED')),
    CONSTRAINT ck_participants_birth_year CHECK (
        date_of_birth_year IS NULL OR date_of_birth_year BETWEEN 1900 AND 2100),
    CONSTRAINT ck_participants_sex CHECK (
        sex IS NULL OR sex IN ('MALE','FEMALE','OTHER','UNDISCLOSED')),
    -- §20.3: a withdrawal without a date is not a withdrawal anyone can audit.
    CONSTRAINT ck_participants_withdrawal CHECK (
        status <> 'WITHDRAWN' OR withdrawal_date IS NOT NULL)
);
CREATE INDEX ix_participants_trial_site ON participants (trial_id, trial_site_id);
CREATE INDEX ix_participants_status ON participants (status);
CREATE TRIGGER tg_participants_updated_at BEFORE UPDATE ON participants
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── participant_identities ───────────────────────────────────────────────────
-- The re-identification key. Primary key IS the foreign key, so exactly one identity row can
-- exist per participant. Every analytics query, GIS aggregate and export reads `participants`
-- and never opens this table, which turns the §11 privacy guarantee from a promise about
-- careful query writing into a fact about which tables a code path touches.
CREATE TABLE participant_identities (
    participant_id          uuid PRIMARY KEY REFERENCES participants (id) ON DELETE RESTRICT,
    full_name               text NOT NULL,
    date_of_birth           date,
    phone                   text,
    email                   citext,
    address_line            text,
    city                    text,
    state                   text,
    postal_code             text,
    -- Hash only. Supports duplicate-enrolment detection without ever storing the number.
    national_id_hash        text,
    emergency_contact_name  text,
    emergency_contact_phone text,
    version                 integer NOT NULL DEFAULT 1,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_participant_identities_national_id_hash
    ON participant_identities (national_id_hash);
CREATE TRIGGER tg_participant_identities_updated_at BEFORE UPDATE ON participant_identities
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── consents ─────────────────────────────────────────────────────────────────
CREATE TABLE consents (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    participant_id      uuid NOT NULL REFERENCES participants (id) ON DELETE RESTRICT,
    consent_document_id uuid,            -- FK added with `documents`
    consent_version     text NOT NULL,
    consent_type        text NOT NULL DEFAULT 'INITIAL',
    consented_at        timestamptz NOT NULL,
    consent_method      text NOT NULL,
    witness_name        text,
    obtained_by         uuid NOT NULL REFERENCES users (id),
    status              text NOT NULL DEFAULT 'ACTIVE',
    withdrawn_at        timestamptz,
    withdrawal_reason   text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_consents_type CHECK (consent_type IN ('INITIAL','RE_CONSENT','AMENDMENT')),
    CONSTRAINT ck_consents_method CHECK (consent_method IN (
        'WRITTEN','ELECTRONIC','WITNESSED_VERBAL')),
    CONSTRAINT ck_consents_status CHECK (status IN ('ACTIVE','SUPERSEDED','WITHDRAWN')),
    CONSTRAINT ck_consents_withdrawal CHECK (
        status <> 'WITHDRAWN' OR withdrawn_at IS NOT NULL)
);
-- One active consent per participant. Two would make "is this participant consented" a
-- question with two answers, at the moment clinical writes depend on it.
CREATE UNIQUE INDEX uq_consents_one_active ON consents (participant_id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_consents_participant ON consents (participant_id);

-- ── visits ───────────────────────────────────────────────────────────────────
CREATE TABLE visits (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    participant_id    uuid NOT NULL REFERENCES participants (id) ON DELETE RESTRICT,
    visit_name        text NOT NULL,
    visit_number      integer NOT NULL,
    scheduled_date    date NOT NULL,
    window_start_date date,
    window_end_date   date,
    actual_date       date,
    status            text NOT NULL DEFAULT 'SCHEDULED',
    performed_by      uuid REFERENCES users (id),
    notes             text,
    version           integer NOT NULL DEFAULT 1,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_visits_participant_number UNIQUE (participant_id, visit_number),
    CONSTRAINT ck_visits_status CHECK (status IN (
        'SCHEDULED','COMPLETED','MISSED','CANCELLED','OUT_OF_WINDOW')),
    CONSTRAINT ck_visits_window_order CHECK (
        window_end_date IS NULL OR window_start_date IS NULL
        OR window_end_date >= window_start_date)
);
CREATE INDEX ix_visits_participant ON visits (participant_id);
CREATE INDEX ix_visits_scheduled ON visits (scheduled_date, status);
CREATE TRIGGER tg_visits_updated_at BEFORE UPDATE ON visits
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── observations ─────────────────────────────────────────────────────────────
-- The growth table: §3.2 of the design spec sizes this in the billions. Partitioning lands in
-- a later migration once the access patterns are settled.
CREATE TABLE observations (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id            uuid NOT NULL REFERENCES visits (id) ON DELETE RESTRICT,
    observation_code    text NOT NULL,
    observation_name    text NOT NULL,
    category            text NOT NULL,
    value_numeric       numeric(12,4),
    value_text          text,
    value_boolean       boolean,
    unit                text,
    reference_range_low numeric(12,4),
    reference_range_high numeric(12,4),
    -- Clinician judgement, not derived: a value inside the reference range may still be
    -- clinically abnormal for a given participant.
    is_abnormal         boolean,
    recorded_at         timestamptz NOT NULL DEFAULT now(),
    status              text NOT NULL DEFAULT 'RECORDED',
    amendment_reason    text,
    version             integer NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid REFERENCES users (id),
    updated_by          uuid REFERENCES users (id),
    CONSTRAINT uq_observations_visit_code UNIQUE (visit_id, observation_code),
    CONSTRAINT ck_observations_category CHECK (category IN (
        'VITAL_SIGN','LABORATORY','PHYSICAL_EXAM','QUESTIONNAIRE','IMAGING','OTHER')),
    CONSTRAINT ck_observations_status CHECK (status IN (
        'RECORDED','AMENDED','QUERIED','VERIFIED')),
    -- An amendment with no reason is an unexplained change to a clinical record.
    CONSTRAINT ck_observations_amendment_reason CHECK (
        status <> 'AMENDED' OR amendment_reason IS NOT NULL),
    -- A row carrying no value at all is a data-entry bug, not an observation.
    CONSTRAINT ck_observations_has_value CHECK (
        value_numeric IS NOT NULL OR value_text IS NOT NULL OR value_boolean IS NOT NULL)
);
CREATE INDEX ix_observations_visit ON observations (visit_id);
CREATE INDEX ix_observations_recorded_at ON observations (recorded_at);
CREATE TRIGGER tg_observations_updated_at BEFORE UPDATE ON observations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── medications ──────────────────────────────────────────────────────────────
CREATE TABLE medications (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    participant_id  uuid NOT NULL REFERENCES participants (id) ON DELETE RESTRICT,
    medication_name text NOT NULL,
    -- Required, not optional. Distinguishing study drug from concomitant from rescue is what
    -- causality assessment rests on (§8.17); an untyped medication row cannot be interpreted
    -- during a safety review.
    medication_type text NOT NULL,
    -- numeric, not text: a dose recorded as "two tablets" cannot be compared, summed or
    -- range-checked, and this column feeds dosing analyses.
    dose            numeric(10,3),
    dose_unit       text,
    frequency       text,
    route           text,
    indication      text,
    start_date      date NOT NULL,
    end_date        date,
    is_ongoing      boolean NOT NULL DEFAULT true,
    version         integer NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_medications_type CHECK (medication_type IN (
        'STUDY_DRUG','CONCOMITANT','RESCUE')),
    CONSTRAINT ck_medications_route CHECK (route IS NULL OR route IN (
        'ORAL','IV','IM','SC','TOPICAL','INHALED','OTHER')),
    CONSTRAINT ck_medications_date_order CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT ck_medications_ongoing CHECK (NOT is_ongoing OR end_date IS NULL)
);
CREATE INDEX ix_medications_participant ON medications (participant_id);
CREATE TRIGGER tg_medications_updated_at BEFORE UPDATE ON medications
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
