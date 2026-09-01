-- V1 — baseline.
--
-- Extensions only. The 23 tables, their constraints, indexes, partitions and RLS
-- policies arrive in B3; this migration exists so that an empty database is a
-- migrated database, and so the PostGIS dependency is proven from the first build.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
