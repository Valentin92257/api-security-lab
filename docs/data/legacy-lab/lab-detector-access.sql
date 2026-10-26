-- Bootstrap administrator, once after lab schema/guards/projections. NOLOGIN capability roles.
BEGIN;
CREATE ROLE lab_detector NOLOGIN;
CREATE ROLE lab_control NOLOGIN;
CREATE ROLE lab_log_ingestor NOLOGIN;
GRANT USAGE ON SCHEMA detection, audit TO lab_detector;
GRANT SELECT ON audit.detector_observations TO lab_detector;
GRANT SELECT ON ALL TABLES IN SCHEMA detection TO lab_detector;
GRANT INSERT ON detection.processed_events, detection.incidents TO lab_detector;
GRANT INSERT, UPDATE, DELETE ON detection.rule_state TO lab_detector;
GRANT UPDATE (analysis_state, revision, updated_at, summary, severity, confidence_level,
  impact_status, evidence_event_ids, history) ON detection.incidents TO lab_detector;
GRANT UPDATE (state, processed_count, errors, finished_at) ON detection.jobs TO lab_detector;
GRANT USAGE ON SCHEMA control, audit, detection TO lab_control;
GRANT SELECT ON ALL TABLES IN SCHEMA control, audit, detection TO lab_control;
GRANT INSERT ON ALL TABLES IN SCHEMA control TO lab_control;
GRANT UPDATE (state, revision, cleanup_safe, failure_reason, finished_at, fixture_manifest,
  fixture_hash, command_journal) ON control.runs TO lab_control;
GRANT UPDATE (state, step_manifest, step_manifest_hash, sealed_at)
  ON control.scenario_jobs TO lab_control;
GRANT INSERT ON audit.log_streams TO lab_control;
GRANT USAGE ON SCHEMA audit TO lab_log_ingestor;
GRANT SELECT ON audit.log_streams, audit.security_events TO lab_log_ingestor;
GRANT INSERT ON audit.security_events TO lab_log_ingestor;
GRANT UPDATE (state,last_ingested_seq,expected_last_seq,end_seen_at,error_reason)
  ON audit.log_streams TO lab_log_ingestor;
GRANT INSERT ON detection.partitions, detection.jobs TO lab_control;
GRANT UPDATE (status, revision, updated_at, history) ON detection.incidents TO lab_control;
GRANT EXECUTE ON FUNCTION audit.canonical_event_ids(uuid[]), audit.event_set_digest(uuid[]),
  audit.assert_event_set(uuid[],bytea), detection.assert_json_append(jsonb,jsonb)
  TO lab_detector, lab_control;
COMMIT;
-- Dedicated LOGIN identities and shop-role grants are separate bootstrap migrations.
-- Only the log-ingestor module of control uses lab_log_ingestor; gateway/shop have no lab DB credentials.
