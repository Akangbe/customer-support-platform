-- Baseline migration: proves the Flyway/Postgres wiring works.
-- Domain tables land in later phases, one migration per phase, per
-- the module that owns them (tenant, customer, conversation, ...).
CREATE EXTENSION IF NOT EXISTS pgcrypto;
