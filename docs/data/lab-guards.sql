-- After lab-schema.sql, before lab-projections.sql / lab-detector-access.sql.
-- JSON/array aggregation must not weaken immutable snapshots or sealed sets.
BEGIN;
CREATE FUNCTION audit.canonical_event_ids(ids uuid[]) RETURNS uuid[]
LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT coalesce(array_agg(DISTINCT id ORDER BY id),'{}'::uuid[]) FROM unnest(ids) id
$$;
CREATE FUNCTION audit.event_set_digest(ids uuid[]) RETURNS bytea
LANGUAGE sql IMMUTABLE STRICT AS $$
  SELECT sha256(convert_to(coalesce(string_agg(id::text || chr(10),'' ORDER BY id),''),'UTF8'))
  FROM unnest(ids) id
$$;
CREATE FUNCTION audit.assert_event_set(ids uuid[], digest bytea) RETURNS void
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
  IF ids IS NULL OR digest IS NULL OR array_position(ids,NULL) IS NOT NULL
     OR ids IS DISTINCT FROM audit.canonical_event_ids(ids)
     OR digest IS DISTINCT FROM audit.event_set_digest(ids) THEN
    RAISE EXCEPTION 'Invalid canonical event set or hash';
  END IF;
END $$;
CREATE FUNCTION audit.guard_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='INSERT' AND NEW.state<>'OPEN' THEN
    RAISE EXCEPTION 'A producer epoch must start OPEN';
  END IF;
  IF TG_OP='UPDATE' THEN
    IF ROW(NEW.id,NEW.run_id,NEW.partition_id,NEW.producer_kind,NEW.source,NEW.registered_at)
      IS DISTINCT FROM ROW(OLD.id,OLD.run_id,OLD.partition_id,OLD.producer_kind,OLD.source,OLD.registered_at) THEN
      RAISE EXCEPTION 'Producer binding is immutable';
    END IF;
    IF OLD.state='SEALED' AND NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION 'A sealed epoch is immutable';
    END IF;
    IF NEW.state<>OLD.state AND NOT
      ((OLD.state='OPEN' AND NEW.state='CLOSED') OR (OLD.state='CLOSED' AND NEW.state='SEALED')) THEN
      RAISE EXCEPTION 'Invalid producer state transition';
    END IF;
  END IF;
  IF NEW.state='SEALED' THEN
    PERFORM audit.assert_event_set(NEW.expected_event_ids,NEW.event_set_hash);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER epoch_immutable BEFORE INSERT OR UPDATE ON audit.producer_epochs
FOR EACH ROW EXECUTE FUNCTION audit.guard_epoch();

CREATE FUNCTION detection.guard_job() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='UPDATE' AND
    ROW(NEW.id,NEW.partition_id,NEW.ruleset_hash,NEW.kind,NEW.manifest_hash,NEW.expected_count,NEW.expected_event_ids,NEW.created_at)
    IS DISTINCT FROM
    ROW(OLD.id,OLD.partition_id,OLD.ruleset_hash,OLD.kind,OLD.manifest_hash,OLD.expected_count,OLD.expected_event_ids,OLD.created_at) THEN
    RAISE EXCEPTION 'Detector assignment is immutable';
  END IF;
  IF NEW.kind='CLOSE' THEN
    PERFORM audit.assert_event_set(NEW.expected_event_ids,NEW.manifest_hash);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER job_input_immutable BEFORE INSERT OR UPDATE ON detection.jobs
FOR EACH ROW EXECUTE FUNCTION detection.guard_job();

CREATE FUNCTION detection.assert_json_append(old_items jsonb, new_items jsonb) RETURNS void
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
  IF jsonb_typeof(new_items) IS DISTINCT FROM 'array'
     OR jsonb_array_length(new_items)<jsonb_array_length(old_items) THEN
    RAISE EXCEPTION 'History must be an append-only array';
  END IF;
  IF EXISTS (SELECT 1 FROM jsonb_array_elements(old_items) WITH ORDINALITY AS x(value,n)
             WHERE value IS DISTINCT FROM new_items->((n-1)::integer)) THEN
    RAISE EXCEPTION 'Existing history entries are immutable';
  END IF;
END $$;
CREATE FUNCTION control.guard_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.id,NEW.experiment_id,NEW.target,NEW.repetition,NEW.partition_id,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.id,OLD.experiment_id,OLD.target,OLD.repetition,OLD.partition_id,OLD.created_at) THEN
    RAISE EXCEPTION 'Run assignment is immutable';
  END IF;
  IF OLD.fixture_manifest IS NOT NULL AND
    ROW(NEW.fixture_manifest,NEW.fixture_hash) IS DISTINCT FROM ROW(OLD.fixture_manifest,OLD.fixture_hash) THEN
    RAISE EXCEPTION 'Prepared fixture baseline is immutable';
  END IF;
  PERFORM detection.assert_json_append(OLD.command_journal,NEW.command_journal);
  RETURN NEW;
END $$;
CREATE TRIGGER run_baseline_immutable BEFORE UPDATE ON control.runs
FOR EACH ROW EXECUTE FUNCTION control.guard_run();
CREATE FUNCTION control.guard_runner_job() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.id,NEW.run_id,NEW.epoch,NEW.attempt,NEW.plan,NEW.plan_hash,NEW.lease_token_hash)
    IS DISTINCT FROM ROW(OLD.id,OLD.run_id,OLD.epoch,OLD.attempt,OLD.plan,OLD.plan_hash,OLD.lease_token_hash) THEN
    RAISE EXCEPTION 'Runner plan is immutable';
  END IF;
  IF OLD.sealed_at IS NOT NULL AND
    ROW(NEW.step_manifest,NEW.step_manifest_hash,NEW.sealed_at)
    IS DISTINCT FROM ROW(OLD.step_manifest,OLD.step_manifest_hash,OLD.sealed_at) THEN
    RAISE EXCEPTION 'Runner manifest is immutable';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER runner_manifest_immutable BEFORE UPDATE ON control.runner_jobs
FOR EACH ROW EXECUTE FUNCTION control.guard_runner_job();
CREATE FUNCTION detection.guard_incident() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF array_position(NEW.evidence_event_ids,NULL) IS NOT NULL
     OR NEW.evidence_event_ids IS DISTINCT FROM audit.canonical_event_ids(NEW.evidence_event_ids) THEN
    RAISE EXCEPTION 'Evidence must contain sorted unique UUIDs';
  END IF;
  IF TG_OP='UPDATE' THEN
    IF NOT NEW.evidence_event_ids @> OLD.evidence_event_ids THEN
      RAISE EXCEPTION 'Existing evidence cannot be removed';
    END IF;
    PERFORM detection.assert_json_append(OLD.history,NEW.history);
    IF NEW.revision<>OLD.revision+1 OR jsonb_array_length(NEW.history)<>jsonb_array_length(OLD.history)+1 THEN
      RAISE EXCEPTION 'Incident update requires one revision and one history entry';
    END IF;
  ELSE
    IF NEW.revision<>0 OR jsonb_array_length(NEW.history)<>1 THEN
      RAISE EXCEPTION 'Incident creation requires initial history revision 0';
    END IF;
  END IF;
  IF (NEW.history -> -1) ->> 'revision' IS DISTINCT FROM NEW.revision::text
     OR (NEW.history -> -1) ->> 'analysisState' IS DISTINCT FROM NEW.analysis_state
     OR (NEW.history -> -1) ->> 'status' IS DISTINCT FROM NEW.status THEN
    RAISE EXCEPTION 'Last history entry does not match incident state';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER incident_history_immutable BEFORE INSERT OR UPDATE ON detection.incidents
FOR EACH ROW EXECUTE FUNCTION detection.guard_incident();
REVOKE ALL ON FUNCTION audit.canonical_event_ids(uuid[]), audit.event_set_digest(uuid[]),
  audit.assert_event_set(uuid[],bytea), detection.assert_json_append(jsonb,jsonb) FROM PUBLIC;
COMMIT;
