ALTER TABLE control.step_results ADD COLUMN order_evidence jsonb;

ALTER TABLE control.step_results ADD CONSTRAINT step_results_order_evidence_check CHECK (
    order_evidence IS NULL OR (
        method = 'GET' AND route ~ '^/api/v1/orders/[1-9][0-9]*$'
        AND status = 200 AND outcome = 'RESPONSE'
        AND jsonb_typeof(order_evidence) = 'object'
    )
);

CREATE TABLE control.bola_assessments (
    trial_id uuid NOT NULL REFERENCES control.trials (trial_id),
    run_id uuid NOT NULL,
    target varchar(16) NOT NULL CHECK (target IN ('VULNERABLE', 'PROTECTED')),
    kind varchar(16) NOT NULL CHECK (kind IN ('VICTIM_ORDER', 'OWN_ORDER', 'MISSING_ORDER')),
    order_id bigint NOT NULL CHECK (order_id > 0),
    effect varchar(24) NOT NULL CHECK (effect IN ('EFFECT_OBSERVED', 'EFFECT_NOT_OBSERVED', 'INCONCLUSIVE')),
    request_id uuid REFERENCES control.step_results (request_id),
    reason varchar(500) NOT NULL,
    expected_evidence jsonb CHECK (jsonb_typeof(expected_evidence) = 'object'),
    PRIMARY KEY (trial_id, run_id, target, kind, order_id)
);

GRANT SELECT, INSERT ON control.bola_assessments TO control_app;
