-- V4 — organisational and trial structure (§8.7–§8.10).
--
-- This migration also closes the forward reference left open in V2: users.institution_id
-- was created without a foreign key because `institutions` did not yet exist.

-- ── institutions ─────────────────────────────────────────────────────────────
CREATE TABLE institutions (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 text NOT NULL,
    registration_number  text,
    institution_type     text NOT NULL,
    address_line         text,
    city                 text NOT NULL,
    state                text NOT NULL,
    country              text NOT NULL DEFAULT 'India',
    postal_code          text,
    latitude             numeric(9,6),
    longitude            numeric(9,6),
    -- §10.2: generated, never written by the application. A stored point and a pair of
    -- coordinates that can be updated independently will eventually disagree, and the map
    -- would then show a site somewhere it is not.
    location             geography(Point,4326)
        GENERATED ALWAYS AS (
            CASE WHEN latitude IS NOT NULL AND longitude IS NOT NULL
                 THEN ST_SetSRID(ST_MakePoint(longitude::double precision,
                                              latitude::double precision), 4326)::geography
            END
        ) STORED,
    has_ethics_committee boolean NOT NULL DEFAULT false,
    status               text NOT NULL DEFAULT 'ACTIVE',
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_institutions_registration_number UNIQUE (registration_number),
    CONSTRAINT ck_institutions_type CHECK (institution_type IN (
        'GOVERNMENT_HOSPITAL','PRIVATE_HOSPITAL','MEDICAL_COLLEGE','RESEARCH_CENTRE','CRO')),
    CONSTRAINT ck_institutions_status CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED')),
    CONSTRAINT ck_institutions_lat_range CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_institutions_lon_range CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    -- Half a coordinate is worse than none: it silently places the institution on the
    -- equator or the prime meridian rather than failing.
    CONSTRAINT ck_institutions_coordinate_pair CHECK ((latitude IS NULL) = (longitude IS NULL))
);
CREATE INDEX ix_institutions_location ON institutions USING GIST (location);
CREATE INDEX ix_institutions_state ON institutions (state);

CREATE TRIGGER tg_institutions_updated_at
    BEFORE UPDATE ON institutions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Close the V2 forward reference now that the target table exists.
ALTER TABLE users
    ADD CONSTRAINT fk_users_institution
    FOREIGN KEY (institution_id) REFERENCES institutions (id) ON DELETE RESTRICT;

-- ── trials ───────────────────────────────────────────────────────────────────
CREATE TABLE trials (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    protocol_number         text NOT NULL,
    ctri_number             text,
    title                   text NOT NULL,
    short_title             text,
    sponsor_institution_id  uuid NOT NULL REFERENCES institutions (id) ON DELETE RESTRICT,
    phase                   text NOT NULL,
    therapeutic_area        text,
    status                  text NOT NULL DEFAULT 'DRAFT',
    target_enrollment       integer,
    current_enrollment      integer NOT NULL DEFAULT 0,
    planned_start_date      date,
    actual_start_date       date,
    planned_end_date        date,
    actual_end_date         date,
    regulatory_status       text NOT NULL DEFAULT 'NOT_SUBMITTED',
    version                 integer NOT NULL DEFAULT 1,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid REFERENCES users (id),
    updated_by              uuid REFERENCES users (id),
    CONSTRAINT uq_trials_protocol_number UNIQUE (protocol_number),
    CONSTRAINT uq_trials_ctri_number UNIQUE (ctri_number),
    CONSTRAINT ck_trials_phase CHECK (phase IN ('I','II','III','IV','OBSERVATIONAL')),
    CONSTRAINT ck_trials_status CHECK (status IN (
        'DRAFT','PENDING_ETHICS','APPROVED','ACTIVE','SUSPENDED','COMPLETED','TERMINATED',
        'ARCHIVED')),
    CONSTRAINT ck_trials_regulatory_status CHECK (regulatory_status IN (
        'NOT_SUBMITTED','SUBMITTED','APPROVED','QUERY_RAISED','REJECTED')),
    CONSTRAINT ck_trials_target_enrollment CHECK (
        target_enrollment IS NULL OR target_enrollment > 0),
    -- §14.2: enrolment is maintained transactionally, so the database is the thing that
    -- guarantees it never exceeds target or goes negative — not the service that writes it.
    CONSTRAINT ck_trials_enrollment_bounds CHECK (
        current_enrollment >= 0
        AND (target_enrollment IS NULL OR current_enrollment <= target_enrollment)),
    CONSTRAINT ck_trials_date_order CHECK (
        planned_end_date IS NULL OR planned_start_date IS NULL
        OR planned_end_date >= planned_start_date)
);
CREATE INDEX ix_trials_status ON trials (status);
CREATE INDEX ix_trials_sponsor ON trials (sponsor_institution_id);

CREATE TRIGGER tg_trials_updated_at
    BEFORE UPDATE ON trials
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── trial_sites ──────────────────────────────────────────────────────────────
CREATE TABLE trial_sites (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trial_id           uuid NOT NULL REFERENCES trials (id) ON DELETE RESTRICT,
    institution_id     uuid NOT NULL REFERENCES institutions (id) ON DELETE RESTRICT,
    site_code          text NOT NULL,
    status             text NOT NULL DEFAULT 'PLANNED',
    activation_date    date,
    target_enrollment  integer,
    current_enrollment integer NOT NULL DEFAULT 0,
    latitude           numeric(9,6),
    longitude          numeric(9,6),
    -- A site's own coordinates are an override for a distinct campus; most sites sit at
    -- their institution. The fallback is resolved by the trial_sites_located view below
    -- rather than duplicated here, because a generated column cannot read another table.
    location           geography(Point,4326)
        GENERATED ALWAYS AS (
            CASE WHEN latitude IS NOT NULL AND longitude IS NOT NULL
                 THEN ST_SetSRID(ST_MakePoint(longitude::double precision,
                                              latitude::double precision), 4326)::geography
            END
        ) STORED,
    version            integer NOT NULL DEFAULT 1,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_trial_sites_trial_institution UNIQUE (trial_id, institution_id),
    CONSTRAINT uq_trial_sites_trial_code UNIQUE (trial_id, site_code),
    CONSTRAINT ck_trial_sites_status CHECK (status IN (
        'PLANNED','ACTIVATED','ENROLLING','CLOSED_TO_ENROLLMENT','COMPLETED','SUSPENDED')),
    CONSTRAINT ck_trial_sites_lat_range CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_trial_sites_lon_range CHECK (
        longitude IS NULL OR longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_trial_sites_coordinate_pair CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT ck_trial_sites_enrollment_bounds CHECK (
        current_enrollment >= 0
        AND (target_enrollment IS NULL OR current_enrollment <= target_enrollment))
);
CREATE INDEX ix_trial_sites_location ON trial_sites USING GIST (location);
CREATE INDEX ix_trial_sites_trial ON trial_sites (trial_id);
CREATE INDEX ix_trial_sites_institution ON trial_sites (institution_id);

CREATE TRIGGER tg_trial_sites_updated_at
    BEFORE UPDATE ON trial_sites
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- §10.2: one definition of "where is this site", so no caller has to remember the fallback.
CREATE VIEW trial_sites_located AS
SELECT s.*,
       COALESCE(s.location, i.location) AS effective_location
FROM trial_sites s
JOIN institutions i ON i.id = s.institution_id;

-- ── trial_staff ──────────────────────────────────────────────────────────────
-- The scope table. Every site-scoped RLS policy in V8 resolves through this.
CREATE TABLE trial_staff (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trial_id      uuid NOT NULL REFERENCES trials (id) ON DELETE RESTRICT,
    trial_site_id uuid REFERENCES trial_sites (id) ON DELETE RESTRICT,
    user_id       uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    staff_role    text NOT NULL,
    start_date    date NOT NULL DEFAULT CURRENT_DATE,
    end_date      date,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_trial_staff_role CHECK (staff_role IN (
        'PI','SUB_INVESTIGATOR','COORDINATOR','STAFF','MONITOR')),
    CONSTRAINT ck_trial_staff_date_order CHECK (end_date IS NULL OR end_date >= start_date)
);
-- One live assignment per user per site (NULL site = trial-wide). A duplicate would make
-- "is this user assigned here" ambiguous, which is the question every policy asks.
CREATE UNIQUE INDEX uq_trial_staff_active
    ON trial_staff (trial_id, user_id, COALESCE(trial_site_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE end_date IS NULL;
CREATE INDEX ix_trial_staff_user ON trial_staff (user_id) WHERE end_date IS NULL;
CREATE INDEX ix_trial_staff_trial ON trial_staff (trial_id);
CREATE INDEX ix_trial_staff_site ON trial_staff (trial_site_id);
