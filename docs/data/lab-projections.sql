-- After lab-schema.sql. Owned by migration_owner; runtime roles must not own this view.
BEGIN;
CREATE VIEW audit.detector_observations WITH (security_barrier=true) AS
SELECT e.id AS event_id, e.partition_id, e.request_id, e.trace_id, e.occurred_at,
       p.producer_kind, e.payload->>'type' AS event_type,
       e.payload#>>'{data,request,method}' AS method,
       e.payload#>>'{data,request,route}' AS route,
       e.payload#>>'{data,request,sourceKey}' AS source_key,
       e.payload#>>'{data,actor,id}' AS actor_key,
       e.payload#>>'{data,observations,accountKey}' AS account_key,
       e.payload#>>'{data,action,kind}' AS action_kind,
       e.payload#>>'{data,action,resourceType}' AS resource_type,
       e.payload#>>'{data,action,resourceId}' AS resource_id,
       e.payload#>>'{data,outcome,status}' AS outcome_status,
       (e.payload#>>'{data,outcome,httpStatus}')::integer AS http_status,
       e.payload#>>'{data,outcome,reasonCode}' AS reason_code,
       (e.payload#>>'{data,observations,subjectMatchesOwner}')::boolean AS subject_matches_owner,
       (e.payload#>>'{data,observations,operationExecuted}')::boolean AS operation_executed,
       (e.payload#>>'{data,observations,stateChanged}')::boolean AS state_changed,
       (e.payload#>>'{data,observations,quantityWithinBounds}')::boolean AS quantity_within_bounds,
       (e.payload#>>'{data,observations,quoteMatchesServer}')::boolean AS quote_matches_server,
       e.payload#>>'{data,observations,authOutcome}' AS auth_outcome,
       e.payload#>>'{data,observations,couponEligibility}' AS coupon_eligibility,
       e.payload#>>'{data,observations,idempotencyOutcome}' AS idempotency_outcome,
       e.payload#>>'{data,observations,intentHash}' AS intent_hash,
       e.payload#>>'{data,observations,idempotencyKeyHash}' AS idempotency_key_hash,
       e.payload#>>'{data,observations,cartKey}' AS cart_key,
       (e.payload#>>'{data,observations,cartRevision}')::bigint AS cart_revision,
       e.payload#>>'{data,observations,orderId}' AS order_id,
       ARRAY(SELECT jsonb_array_elements_text(COALESCE(
          e.payload#>'{data,observations,validationCategories}','[]'::jsonb))) AS validation_categories
FROM audit.security_events e JOIN audit.log_streams p ON p.id=e.stream_id;

-- Log protocol diagnostics. Evaluated at DRAINING, after the bounded wait for STREAM_END.
CREATE VIEW audit.log_gaps AS
SELECT 'STREAM_NOT_DRAINED'::text AS kind, id AS stream_id, id AS reference_id
FROM audit.log_streams WHERE state<>'DRAINED'
UNION ALL
SELECT 'COUNT_MISMATCH',s.id,s.id FROM audit.log_streams s
WHERE s.state='DRAINED' AND s.expected_last_seq<>(SELECT count(*) FROM audit.security_events e WHERE e.stream_id=s.id)
UNION ALL
SELECT 'MISSING_TERMINAL',started.stream_id,started.request_id
FROM audit.security_events started
WHERE started.payload->>'type' IN ('lab.http.request.started.v1','lab.api.request.started.v1')
 AND NOT EXISTS (
   SELECT 1 FROM audit.security_events terminal
   WHERE terminal.stream_id=started.stream_id AND terminal.request_id=started.request_id AND terminal.seq>started.seq
     AND terminal.payload->>'type'=CASE started.payload->>'type'
       WHEN 'lab.http.request.started.v1' THEN 'lab.http.request.completed.v1'
       ELSE 'lab.api.request.completed.v1' END)
UNION ALL
SELECT 'MISSING_API_RESULT',g.stream_id,g.request_id
FROM audit.security_events g
WHERE g.payload->>'type'='lab.http.request.completed.v1'
 AND g.payload#>>'{data,observations,downstreamInvoked}'='true'
 AND NOT EXISTS (SELECT 1 FROM audit.security_events a WHERE a.run_id=g.run_id AND a.request_id=g.request_id
                 AND a.payload->>'type'='lab.api.request.completed.v1')
UNION ALL
SELECT 'MISSING_START',terminal.stream_id,terminal.request_id
FROM audit.security_events terminal
WHERE terminal.payload->>'type' IN ('lab.http.request.completed.v1','lab.api.request.completed.v1')
 AND NOT EXISTS (SELECT 1 FROM audit.security_events started
   WHERE started.stream_id=terminal.stream_id AND started.request_id=terminal.request_id AND started.seq<terminal.seq
     AND started.payload->>'type'=CASE terminal.payload->>'type'
       WHEN 'lab.http.request.completed.v1' THEN 'lab.http.request.started.v1'
       ELSE 'lab.api.request.started.v1' END)
UNION ALL
SELECT 'DUPLICATE_LIFECYCLE',stream_id,request_id
FROM audit.security_events
WHERE payload->>'type' IN ('lab.http.request.started.v1','lab.http.request.completed.v1',
                           'lab.api.request.started.v1','lab.api.request.completed.v1')
GROUP BY stream_id,request_id,payload->>'type' HAVING count(*)>1;
REVOKE ALL ON audit.detector_observations, audit.log_gaps FROM PUBLIC;
COMMIT;
