CREATE TABLE control.step_results (
    request_id uuid PRIMARY KEY,
    trial_id uuid NOT NULL REFERENCES control.trials (trial_id),
    run_id uuid NOT NULL,
    target varchar(16) NOT NULL CHECK (target IN ('VULNERABLE', 'PROTECTED')),
    method varchar(16) NOT NULL,
    route varchar(2048) NOT NULL CHECK (route LIKE '/%' AND route !~ '[?#]'),
    status integer,
    outcome varchar(24) NOT NULL,
    started_at timestamptz NOT NULL,
    duration_millis bigint NOT NULL CHECK (duration_millis >= 0),
    CHECK (
        (outcome = 'RESPONSE' AND status IS NOT NULL AND status BETWEEN 100 AND 599)
        OR (outcome = 'REDIRECT_REJECTED' AND status IS NOT NULL AND status BETWEEN 300 AND 399)
        OR (outcome IN ('TIMEOUT', 'IO_ERROR', 'INTERRUPTED') AND status IS NULL)
    )
);

CREATE INDEX step_results_trial_run ON control.step_results (trial_id, run_id);

GRANT SELECT, INSERT ON control.step_results TO control_app;
