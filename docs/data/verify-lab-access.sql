-- Execute in lab after the four lab SQL files, as bootstrap administrator.
-- Uses no seed/business data and rolls back the test transaction.
BEGIN;
SET LOCAL ROLE lab_detector;
SELECT * FROM audit.detector_observations LIMIT 0;
SELECT * FROM detection.incidents LIMIT 0;
DO $$
BEGIN
  BEGIN
    PERFORM 1 FROM audit.security_events LIMIT 0;
    RAISE EXCEPTION 'Detector unexpectedly reads raw audit';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    PERFORM 1 FROM control.runs LIMIT 0;
    RAISE EXCEPTION 'Detector unexpectedly reads ground truth';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  IF has_column_privilege(current_user,'detection.jobs','expected_event_ids','UPDATE') THEN
    RAISE EXCEPTION 'Detector unexpectedly writes CLOSE set';
  END IF;
END $$;
RESET ROLE;
SET LOCAL ROLE lab_log_ingestor;
SELECT * FROM audit.log_streams LIMIT 0;
SELECT * FROM audit.security_events LIMIT 0;
DO $$
BEGIN
  IF has_table_privilege(current_user,'control.runs','SELECT')
     OR has_table_privilege(current_user,'audit.security_events','UPDATE')
     OR has_column_privilege(current_user,'audit.log_streams','source_instance_id','UPDATE') THEN
    RAISE EXCEPTION 'Log ingestor has excessive privileges';
  END IF;
END $$;
ROLLBACK;
