CREATE TABLE control.trials (
    trial_id uuid PRIMARY KEY,
    idempotency_key uuid NOT NULL UNIQUE,
    requested_seed integer CHECK (requested_seed >= 0),
    families jsonb NOT NULL CHECK (
        jsonb_typeof(families) = 'array'
        AND jsonb_array_length(families) BETWEEN 1 AND 5
        AND families <@ '["CREDENTIAL_STUFFING","BOLA","REPLAY","INJECTION","BUSINESS_LOGIC"]'::jsonb
    ),
    seed integer NOT NULL CHECK (seed >= 0),
    state varchar(16) NOT NULL CHECK (state IN (
        'PREPARING', 'RUNNING', 'FINALIZING', 'COMPLETED', 'FAILED', 'CANCELLED', 'INCONCLUSIVE'
    )),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    planned_steps bigint NOT NULL DEFAULT 0 CHECK (planned_steps BETWEEN 0 AND 9007199254740991),
    finished_steps bigint NOT NULL DEFAULT 0 CHECK (finished_steps BETWEEN 0 AND planned_steps),
    cleanup_safe boolean NOT NULL DEFAULT false,
    error varchar(500)
);

CREATE TABLE control.execution_slot (
    id smallint PRIMARY KEY CHECK (id = 1),
    trial_id uuid UNIQUE REFERENCES control.trials (trial_id)
);
INSERT INTO control.execution_slot (id) VALUES (1);
CREATE INDEX trials_created_at ON control.trials (created_at DESC, trial_id DESC);

GRANT SELECT, INSERT ON control.trials TO control_app;
GRANT UPDATE (state, cleanup_safe, error) ON control.trials TO control_app;
GRANT SELECT, UPDATE ON control.execution_slot TO control_app;
