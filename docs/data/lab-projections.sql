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
FROM audit.security_events e JOIN audit.producer_epochs p ON p.id=e.producer_epoch;

-- Independent completeness diagnostics. Zero rows does not prove runtime quiescence.
CREATE VIEW audit.delivery_gaps AS
SELECT 'MISSING_EVENT'::text AS kind, p.id AS producer_epoch, x.event_id AS reference_id
FROM audit.producer_epochs p CROSS JOIN LATERAL unnest(p.expected_event_ids) AS x(event_id)
LEFT JOIN audit.security_events e ON e.id=x.event_id AND e.producer_epoch=p.id
WHERE p.state='SEALED' AND e.id IS NULL
UNION ALL
SELECT 'MISSING_TERMINAL', producer_epoch, request_id FROM audit.receipts WHERE terminal_event_id IS NULL
UNION ALL
SELECT 'UNEXPECTED_EVENT', e.producer_epoch, e.id
FROM audit.security_events e JOIN audit.producer_epochs p ON p.id=e.producer_epoch
WHERE p.state='SEALED' AND NOT (e.id=ANY(p.expected_event_ids));
REVOKE ALL ON audit.detector_observations, audit.delivery_gaps FROM PUBLIC;
COMMIT;
