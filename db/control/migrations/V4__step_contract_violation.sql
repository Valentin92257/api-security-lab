ALTER TABLE control.step_results DROP CONSTRAINT step_results_check;

ALTER TABLE control.step_results ADD CONSTRAINT step_results_check CHECK (
    (outcome IN ('RESPONSE', 'CONTRACT_VIOLATION') AND status IS NOT NULL AND status BETWEEN 100 AND 599)
    OR (outcome = 'REDIRECT_REJECTED' AND status IS NOT NULL AND status BETWEEN 300 AND 399)
    OR (outcome IN ('TIMEOUT', 'IO_ERROR', 'INTERRUPTED') AND status IS NULL)
);
