-- Bootstrap administrator, once after lab schema/guards/projections. NOLOGIN capability roles.
BEGIN;
CREATE ROLE lab_detector NOLOGIN;
CREATE ROLE lab_control NOLOGIN;
GRANT USAGE ON SCHEMA detection, audit TO lab_detector;
GRANT SELECT ON audit.detector_observations TO lab_detector;
GRANT SELECT ON ALL TABLES IN SCHEMA detection TO lab_detector;
GRANT INSERT ON detection.processed_events, detection.incidents TO lab_detector;
GRANT INSERT, UPDATE, DELETE ON detection.rule_state TO lab_detector;
GRANT UPDATE (analysis_state, revision, updated_at, summary, severity, confidence_level,
  impact_status, evidence_event_ids, history) ON detection.incidents TO lab_detector;
GRANT UPDATE (lease_owner, lease_until, fence) ON detection.partitions TO lab_detector;
GRANT UPDATE (state, processed_count, errors, finished_at) ON detection.jobs TO lab_detector;
GRANT USAGE ON SCHEMA control, audit, detection TO lab_control;
GRANT SELECT ON ALL TABLES IN SCHEMA control, audit, detection TO lab_control;
GRANT INSERT ON ALL TABLES IN SCHEMA control TO lab_control;
GRANT UPDATE (state, revision, cleanup_safe, failure_reason, finished_at, fixture_manifest,
  fixture_hash, command_journal) ON control.runs TO lab_control;
GRANT UPDATE (state, lease_expires_at, step_manifest, step_manifest_hash, sealed_at)
  ON control.runner_jobs TO lab_control;
GRANT INSERT ON audit.producer_epochs TO lab_control;
GRANT UPDATE (state, expected_event_ids, event_set_hash, coverage, sealed_at)
  ON audit.producer_epochs TO lab_control;
GRANT INSERT ON detection.partitions, detection.jobs TO lab_control;
GRANT UPDATE (status, revision, updated_at, history) ON detection.incidents TO lab_control;
GRANT EXECUTE ON FUNCTION audit.canonical_event_ids(uuid[]), audit.event_set_digest(uuid[]),
  audit.assert_event_set(uuid[],bytea), detection.assert_json_append(jsonb,jsonb)
  TO lab_detector, lab_control;
COMMIT;
-- Dedicated LOGIN identities, producer ingestion and shop-role grants are separate bootstrap migrations.
