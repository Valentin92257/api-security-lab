-- PostgreSQL 17. Empty database, migration_owner only. No seed data.
BEGIN;
CREATE SCHEMA control;
CREATE SCHEMA audit;
CREATE SCHEMA detection;
-- Неизменяемая definition с cases/episodes, labels, oracle ids и snapshots конфигураций.
CREATE TABLE control.experiments (
  id uuid NOT NULL,
  name varchar(120) NOT NULL,
  definition_version integer NOT NULL DEFAULT 1,
  definition jsonb NOT NULL,
  definition_hash bytea NOT NULL,
  seed bigint NOT NULL,
  business_time_base timestamptz NOT NULL,
  created_at timestamptz NOT NULL,
  PRIMARY KEY (id),
  CHECK (octet_length(definition_hash) = 32),
  CHECK (definition_version = 1)
);
-- Один target и одна фикстура; связь partition с target приватна.
CREATE TABLE control.runs (
  id uuid NOT NULL,
  experiment_id uuid NOT NULL,
  target varchar(16) NOT NULL,
  repetition integer NOT NULL,
  partition_id uuid NOT NULL,
  state varchar(16) NOT NULL DEFAULT 'CREATED',
  revision bigint NOT NULL DEFAULT 0,
  cleanup_safe boolean NOT NULL DEFAULT false,
  failure_reason text,
  created_at timestamptz NOT NULL,
  finished_at timestamptz,
  fixture_manifest jsonb,
  fixture_hash bytea,
  command_journal jsonb NOT NULL DEFAULT '[]'::jsonb,
  PRIMARY KEY (id),
  UNIQUE (partition_id),
  UNIQUE (experiment_id, target, repetition),
  UNIQUE (id, partition_id),
  CHECK (target IN ('VULNERABLE','PROTECTED')),
  CHECK (state IN ('CREATED','PREPARING','RUNNING','DRAINING','EVALUATING','COMPLETED','FAILED','CANCELLED','INCONCLUSIVE')),
  CHECK (repetition > 0),
  CHECK (revision >= 0),
  CHECK ((fixture_manifest IS NULL) = (fixture_hash IS NULL)),
  CHECK (fixture_hash IS NULL OR octet_length(fixture_hash) = 32),
  CHECK (jsonb_typeof(command_journal) = 'array'),
  CHECK (jsonb_array_length(command_journal) <= 1024)
);
-- Неизменяемый план встроенного runner; один job на run, без распределенного исполнения.
CREATE TABLE control.scenario_jobs (
  id uuid NOT NULL,
  run_id uuid NOT NULL,
  epoch uuid NOT NULL,
  plan jsonb NOT NULL,
  plan_hash bytea NOT NULL,
  deadline_at timestamptz NOT NULL,
  state varchar(16) NOT NULL,
  step_manifest jsonb,
  step_manifest_hash bytea,
  sealed_at timestamptz,
  PRIMARY KEY (id),
  UNIQUE (run_id),
  CHECK (state IN ('PREPARED','RUNNING','STOPPED','FAILED')),
  CHECK (octet_length(plan_hash) = 32),
  CHECK ((step_manifest IS NULL) = (step_manifest_hash IS NULL)),
  CHECK ((step_manifest IS NULL) = (sealed_at IS NULL)),
  CHECK (step_manifest_hash IS NULL OR octet_length(step_manifest_hash) = 32)
);
-- Один реальный HTTP-attempt, включая retry и timeout.
CREATE TABLE control.attempts (
  request_id uuid NOT NULL,
  step_id uuid NOT NULL,
  attempt_no integer NOT NULL,
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  http_status integer,
  transport_outcome text NOT NULL,
  response_evidence jsonb NOT NULL,
  job_id uuid NOT NULL,
  PRIMARY KEY (request_id),
  UNIQUE (job_id, step_id, attempt_no)
);
-- Immutable report revision: snapshot и body с caseResults, episodeResults, incidentAssessments и evidence.
CREATE TABLE control.reports (
  id uuid NOT NULL,
  run_id uuid NOT NULL,
  revision integer NOT NULL,
  evaluator_version text NOT NULL,
  completeness text NOT NULL,
  body jsonb NOT NULL,
  body_hash bytea NOT NULL,
  created_at timestamptz NOT NULL,
  snapshot jsonb,
  snapshot_hash bytea,
  snapshot_captured_at timestamptz,
  PRIMARY KEY (id),
  UNIQUE (run_id, revision),
  CHECK (revision > 0),
  CHECK (completeness IN ('COMPLETE','INCONCLUSIVE')),
  CHECK (octet_length(body_hash) = 32),
  CHECK ((snapshot IS NULL) = (snapshot_hash IS NULL)),
  CHECK ((snapshot IS NULL) = (snapshot_captured_at IS NULL)),
  CHECK (snapshot_hash IS NULL OR octet_length(snapshot_hash) = 32),
  CHECK (completeness <> 'COMPLETE' OR snapshot IS NOT NULL)
);
-- Один JSONL-файл producer на run; collector отмечает прочтение STREAM_END и всего диапазона seq.
CREATE TABLE audit.log_streams (
  id uuid NOT NULL,
  run_id uuid NOT NULL,
  partition_id uuid NOT NULL,
  producer_kind text NOT NULL,
  source text NOT NULL,
  source_instance_id uuid NOT NULL,
  file_name text NOT NULL,
  state text NOT NULL DEFAULT 'READING',
  last_ingested_seq bigint NOT NULL DEFAULT 0,
  expected_last_seq bigint,
  end_seen_at timestamptz,
  error_reason text,
  registered_at timestamptz NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (run_id, producer_kind),
  UNIQUE (id, run_id, partition_id),
  UNIQUE (file_name),
  CHECK (producer_kind IN ('GATEWAY','SHOP')),
  CHECK (state IN ('READING','DRAINED','ERROR')),
  CHECK (last_ingested_seq BETWEEN 0 AND 100000),
  CHECK (expected_last_seq IS NULL OR expected_last_seq BETWEEN 0 AND 100000),
  CHECK ((expected_last_seq IS NULL) = (end_seen_at IS NULL)),
  CHECK (state <> 'DRAINED' OR (expected_last_seq IS NOT NULL AND last_ingested_seq=expected_last_seq AND error_reason IS NULL)),
  CHECK (state <> 'ERROR' OR error_reason IS NOT NULL)
);
-- Валидированное событие из JSONL; append-only; raw payload не доступен detector.
CREATE TABLE audit.security_events (
  id uuid NOT NULL,
  stream_id uuid NOT NULL,
  seq bigint NOT NULL,
  run_id uuid NOT NULL,
  partition_id uuid NOT NULL,
  request_id uuid NOT NULL,
  trace_id char(32) NOT NULL,
  occurred_at timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  payload jsonb NOT NULL,
  payload_hash bytea NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (stream_id, seq),
  CHECK (octet_length(payload_hash) = 32),
  CHECK (jsonb_typeof(payload) = 'object'),
  CHECK (seq BETWEEN 1 AND 100000)
);
-- Неизменяемый ruleset; один worker на partition внутри единственного процесса detector.
CREATE TABLE detection.partitions (
  id uuid NOT NULL,
  ruleset_hash bytea NOT NULL,
  ruleset_snapshot jsonb NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (id, ruleset_hash),
  CHECK (octet_length(ruleset_hash) = 32)
);
-- Sanitized SQL commands; control вставляет, detector обновляет outcome.
CREATE TABLE detection.jobs (
  id uuid NOT NULL,
  partition_id uuid NOT NULL,
  ruleset_hash bytea NOT NULL,
  kind text NOT NULL,
  state text NOT NULL DEFAULT 'PENDING',
  manifest_hash bytea,
  expected_count bigint,
  processed_count bigint,
  errors jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at timestamptz NOT NULL,
  finished_at timestamptz,
  expected_event_ids uuid[],
  PRIMARY KEY (id),
  CHECK (kind IN ('PREPARE','CLOSE','CANCEL')),
  CHECK (state IN ('PENDING','RUNNING','ACK','ERROR')),
  CHECK ((kind = 'CLOSE' AND expected_event_ids IS NOT NULL AND manifest_hash IS NOT NULL AND octet_length(manifest_hash) = 32 AND expected_count IS NOT NULL AND expected_count = cardinality(expected_event_ids)) OR (kind <> 'CLOSE' AND expected_event_ids IS NULL AND manifest_hash IS NULL AND expected_count IS NULL)),
  CHECK (expected_event_ids IS NULL OR cardinality(expected_event_ids) <= 100000),
  CHECK (kind <> 'CLOSE' OR state <> 'ACK' OR (processed_count IS NOT NULL AND processed_count = expected_count AND errors = '[]'::jsonb AND finished_at IS NOT NULL))
);
-- Atomic marker вместе с state/evidence/incident.
CREATE TABLE detection.processed_events (
  partition_id uuid NOT NULL,
  ruleset_hash bytea NOT NULL,
  event_id uuid NOT NULL,
  processed_at timestamptz NOT NULL,
  PRIMARY KEY (partition_id, ruleset_hash, event_id)
);
-- Bounded event-time windows и buffers; версия JSON pinned ruleset.
CREATE TABLE detection.rule_state (
  partition_id uuid NOT NULL,
  ruleset_hash bytea NOT NULL,
  rule_id text NOT NULL,
  correlation_key text NOT NULL,
  window_key text NOT NULL,
  state jsonb NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (partition_id, ruleset_hash, rule_id, correlation_key, window_key)
);
-- Наблюдаемая угроза; не содержит TP/FP и confirmed impact.
CREATE TABLE detection.incidents (
  id uuid NOT NULL,
  partition_id uuid NOT NULL,
  ruleset_hash bytea NOT NULL,
  rule_id text NOT NULL,
  rule_version integer NOT NULL,
  detector_version text NOT NULL,
  correlation_key text NOT NULL,
  window_key text NOT NULL,
  category text NOT NULL,
  technique text NOT NULL,
  tags text[] NOT NULL,
  severity text NOT NULL,
  confidence_level text NOT NULL,
  method text NOT NULL,
  analysis_state text NOT NULL DEFAULT 'PROVISIONAL',
  status text NOT NULL DEFAULT 'OPEN',
  revision bigint NOT NULL DEFAULT 0,
  impact_status text NOT NULL DEFAULT 'UNKNOWN',
  summary jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  detected_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  evidence_event_ids uuid[] NOT NULL DEFAULT '{}'::uuid[],
  history jsonb NOT NULL DEFAULT '[]'::jsonb,
  PRIMARY KEY (id),
  UNIQUE (partition_id, ruleset_hash, rule_id, correlation_key, window_key),
  CHECK (analysis_state IN ('PROVISIONAL','FINAL','RETRACTED')),
  CHECK (status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
  CHECK (impact_status IN ('SUSPECTED','UNKNOWN')),
  CHECK (confidence_level IN ('UNKNOWN','LOW','MEDIUM','HIGH')),
  CHECK (severity IN ('LOW','MEDIUM','HIGH')),
  CHECK (method IN ('RULE','CORRELATION')),
  CHECK (cardinality(evidence_event_ids) <= 4096),
  CHECK (jsonb_typeof(history) = 'array'),
  CHECK (jsonb_array_length(history) <= 1024),
  CHECK (revision >= 0),
  CHECK (rule_version > 0)
);
ALTER TABLE control.runs ADD FOREIGN KEY (experiment_id) REFERENCES control.experiments (id) ON DELETE RESTRICT;
ALTER TABLE control.scenario_jobs ADD FOREIGN KEY (run_id) REFERENCES control.runs (id) ON DELETE RESTRICT;
ALTER TABLE control.attempts ADD FOREIGN KEY (job_id) REFERENCES control.scenario_jobs (id) ON DELETE RESTRICT;
ALTER TABLE control.reports ADD FOREIGN KEY (run_id) REFERENCES control.runs (id) ON DELETE RESTRICT;
ALTER TABLE audit.log_streams ADD FOREIGN KEY (run_id) REFERENCES control.runs (id) ON DELETE RESTRICT;
ALTER TABLE audit.security_events ADD FOREIGN KEY (stream_id) REFERENCES audit.log_streams (id) ON DELETE RESTRICT;
ALTER TABLE audit.security_events ADD FOREIGN KEY (run_id) REFERENCES control.runs (id) ON DELETE RESTRICT;
CREATE INDEX audit_security_events_idx_1 ON audit.security_events (partition_id, occurred_at, id);
CREATE INDEX audit_security_events_idx_2 ON audit.security_events (stream_id);
CREATE INDEX audit_security_events_idx_3 ON audit.security_events (request_id);
CREATE INDEX audit_security_events_idx_4 ON audit.security_events (run_id);
ALTER TABLE detection.jobs ADD FOREIGN KEY (partition_id) REFERENCES detection.partitions (id) ON DELETE RESTRICT;
CREATE INDEX detection_jobs_idx_1 ON detection.jobs (partition_id, state);
ALTER TABLE detection.processed_events ADD FOREIGN KEY (partition_id) REFERENCES detection.partitions (id) ON DELETE RESTRICT;
ALTER TABLE detection.rule_state ADD FOREIGN KEY (partition_id) REFERENCES detection.partitions (id) ON DELETE RESTRICT;
ALTER TABLE detection.incidents ADD FOREIGN KEY (partition_id) REFERENCES detection.partitions (id) ON DELETE RESTRICT;
CREATE INDEX detection_incidents_idx_1 ON detection.incidents (partition_id, analysis_state, created_at);
ALTER TABLE audit.log_streams ADD FOREIGN KEY (run_id, partition_id) REFERENCES control.runs (id, partition_id);
ALTER TABLE audit.security_events ADD FOREIGN KEY (stream_id, run_id, partition_id) REFERENCES audit.log_streams (id, run_id, partition_id);
ALTER TABLE detection.jobs ADD FOREIGN KEY (partition_id, ruleset_hash) REFERENCES detection.partitions (id, ruleset_hash);
ALTER TABLE detection.processed_events ADD FOREIGN KEY (partition_id, ruleset_hash) REFERENCES detection.partitions (id, ruleset_hash);
ALTER TABLE detection.rule_state ADD FOREIGN KEY (partition_id, ruleset_hash) REFERENCES detection.partitions (id, ruleset_hash);
ALTER TABLE detection.incidents ADD FOREIGN KEY (partition_id, ruleset_hash) REFERENCES detection.partitions (id, ruleset_hash);
REVOKE ALL ON SCHEMA control, audit, detection FROM PUBLIC;
COMMIT;
