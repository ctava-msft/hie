CREATE SCHEMA IF NOT EXISTS hie_gold;

CREATE TABLE IF NOT EXISTS hie_gold.fhir_event (
    source text NOT NULL,
    event_id text NOT NULL,
    resource_type text NOT NULL,
    fhir_id text NOT NULL,
    version_time timestamptz NOT NULL,
    event_time timestamptz NOT NULL,
    silver_blob text NOT NULL,
    resource_json jsonb NOT NULL,
    PRIMARY KEY (source, event_id)
);

CREATE TABLE IF NOT EXISTS hie_gold.fhir_current (
    resource_type text NOT NULL,
    fhir_id text NOT NULL,
    version_time timestamptz NOT NULL,
    resource_json jsonb NOT NULL,
    PRIMARY KEY (resource_type, fhir_id)
);
