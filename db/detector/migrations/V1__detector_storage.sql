CREATE TABLE detector.analysis_runs (
    run_id uuid PRIMARY KEY,
    created_instance_id uuid NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('ACCEPTING', 'FINISHING', 'COMPLETED', 'FAILED')),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    finish_request jsonb,
    deadline_at timestamptz,
    completed_at timestamptz,
    error varchar(500),
    CHECK ((finish_request IS NULL) = (deadline_at IS NULL)),
    CHECK (state NOT IN ('FINISHING', 'COMPLETED') OR finish_request IS NOT NULL),
    CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE TABLE detector.producer_progress (
    run_id uuid NOT NULL REFERENCES detector.analysis_runs (run_id),
    producer varchar(7) NOT NULL CHECK (producer IN ('GATEWAY', 'SHOP')),
    received_count integer NOT NULL DEFAULT 0 CHECK (received_count BETWEEN 0 AND 100000),
    max_sequence integer NOT NULL DEFAULT 0 CHECK (max_sequence BETWEEN 0 AND 100000),
    last_sequence integer CHECK (last_sequence BETWEEN 0 AND 100000),
    PRIMARY KEY (run_id, producer),
    CHECK (received_count <= max_sequence),
    CHECK (last_sequence IS NULL OR max_sequence <= last_sequence)
);

CREATE TABLE detector.access_logs (
    run_id uuid NOT NULL,
    producer varchar(7) NOT NULL,
    sequence integer NOT NULL CHECK (sequence BETWEEN 1 AND 100000),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    request_id uuid NOT NULL,
    event_time timestamptz NOT NULL,
    event_nanos integer NOT NULL CHECK (event_nanos BETWEEN 0 AND 999999999),
    received_at timestamptz NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (run_id, producer, sequence),
    FOREIGN KEY (run_id, producer) REFERENCES detector.producer_progress (run_id, producer)
);

CREATE TABLE detector.incidents (
    run_id uuid NOT NULL REFERENCES detector.analysis_runs (run_id),
    incident_id uuid NOT NULL,
    detected_at timestamptz NOT NULL,
    detected_nanos integer NOT NULL CHECK (detected_nanos BETWEEN 0 AND 999999999),
    family varchar(24) CHECK (family IN ('CREDENTIAL_STUFFING', 'BOLA', 'REPLAY', 'INJECTION', 'BUSINESS_LOGIC')),
    request_ids uuid[] NOT NULL CHECK (cardinality(request_ids) BETWEEN 1 AND 10000),
    description varchar(1000) NOT NULL CHECK (length(description) >= 1),
    PRIMARY KEY (run_id, incident_id)
);

CREATE INDEX access_logs_request ON detector.access_logs (run_id, request_id);
CREATE INDEX access_logs_time ON detector.access_logs (run_id, event_time, event_nanos, producer, sequence);
CREATE INDEX incidents_page ON detector.incidents (run_id, detected_at, detected_nanos, incident_id);
CREATE INDEX runs_deadline ON detector.analysis_runs (deadline_at, run_id) WHERE state = 'FINISHING';

GRANT USAGE ON SCHEMA detector TO detector_app;
GRANT SELECT, INSERT, UPDATE ON detector.analysis_runs, detector.producer_progress TO detector_app;
GRANT SELECT, INSERT ON detector.access_logs, detector.incidents TO detector_app;
