ALTER TABLE detector.analysis_runs
    ADD COLUMN input_revision bigint NOT NULL DEFAULT 0 CHECK (input_revision >= 0),
    ADD COLUMN processed_revision bigint NOT NULL DEFAULT 0,
    ADD COLUMN analyzed_at timestamptz,
    ADD CHECK (processed_revision BETWEEN 0 AND input_revision);

CREATE TABLE detector.live_incidents (
    run_id uuid NOT NULL REFERENCES detector.analysis_runs (run_id),
    incident_id uuid NOT NULL,
    detected_at timestamptz NOT NULL,
    detected_nanos integer NOT NULL CHECK (detected_nanos BETWEEN 0 AND 999999999),
    family varchar(24) CHECK (family IN ('CREDENTIAL_STUFFING', 'BOLA', 'REPLAY', 'INJECTION', 'BUSINESS_LOGIC')),
    request_ids uuid[] NOT NULL CHECK (cardinality(request_ids) BETWEEN 1 AND 10000),
    description varchar(1000) NOT NULL CHECK (length(description) >= 1),
    PRIMARY KEY (run_id, incident_id)
);

CREATE INDEX live_incidents_page ON detector.live_incidents
    (run_id, detected_at, detected_nanos, incident_id);
CREATE INDEX runs_live_analysis ON detector.analysis_runs (analyzed_at, created_at, run_id)
    WHERE state IN ('ACCEPTING', 'FINISHING');

GRANT SELECT, INSERT, DELETE ON detector.live_incidents TO detector_app;
