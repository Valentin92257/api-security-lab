package lab.detector.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import lab.contracts.detector.model.AccessLog;
import lab.contracts.detector.model.EventItem;
import lab.contracts.detector.model.Incident;
import lab.contracts.json.ContractJsonCodec;
import lab.detector.analysis.EventCursor;
import lab.detector.analysis.EventFilter;
import lab.detector.analysis.FinishSpec;
import lab.detector.analysis.RunSnapshot;
import lab.detector.engine.AnalysisEngine.Observation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DetectorRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DetectorRepository(JdbcClient jdbc, ContractJsonCodec codec) {
        this.jdbc = jdbc;
        this.json = codec.jsonMapper();
    }

    public boolean insertRun(UUID id, UUID instance) {
        boolean inserted = jdbc.sql("""
                INSERT INTO detector.analysis_runs (run_id, created_instance_id, state)
                VALUES (:id, :instance, 'ACCEPTING') ON CONFLICT DO NOTHING
                """).param("id", id).param("instance", instance).update() == 1;
        if (inserted) {
            jdbc.sql("""
                    INSERT INTO detector.producer_progress (run_id, producer)
                    VALUES (:id, 'GATEWAY'), (:id, 'SHOP')
                    """).param("id", id).update();
        }
        return inserted;
    }

    public Optional<RunSnapshot> find(UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT run_id AS id, state, error, finish_request::text AS finish,
                    input_revision, processed_revision
                FROM detector.analysis_runs WHERE run_id = :id
                """ + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(RunSnapshot.class).optional();
    }

    public Progress progress(UUID id, String producer) {
        return jdbc.sql("""
                SELECT received_count AS count, max_sequence AS max, last_sequence AS last
                FROM detector.producer_progress WHERE run_id = :id AND producer = :producer
                """).param("id", id).param("producer", producer).query(Progress.class).single();
    }

    public Optional<AccessLog> findLog(UUID id, String producer, int sequence) {
        return jdbc.sql("""
                SELECT payload FROM detector.access_logs
                WHERE run_id = :id AND producer = :producer AND sequence = :sequence
                """).param("id", id).param("producer", producer).param("sequence", sequence)
                .query(this::mapLog).optional();
    }

    public List<EventItem> events(EventFilter filter, EventCursor cursor, int limit) {
        var sql = new StringBuilder("SELECT run_id, producer, payload FROM detector.access_logs WHERE TRUE");
        var parameters = new HashMap<String, Object>();
        filter(sql, parameters, "run_id = :runId", "runId", filter.runId());
        filter(sql, parameters, "producer = :producer", "producer", filter.producer());
        filter(sql, parameters, "request_id = :requestId", "requestId", filter.requestId());
        filter(sql, parameters, "payload->>'method' = :method", "method", filter.method());
        filter(sql, parameters, "payload->>'route' = :route", "route", filter.route());
        filter(sql, parameters, "payload->>'remote_addr' = :remoteAddr", "remoteAddr", filter.remoteAddr());
        filter(sql, parameters, "(payload->>'user')::bigint = :userId", "userId", filter.userId());
        if (filter.from() != null) {
            sql.append(" AND (event_time, event_nanos) >= (:from, :fromNanos)");
            parameters.put("from", databaseTime(filter.from()));
            parameters.put("fromNanos", filter.from().getNano());
        }
        if (filter.to() != null) {
            sql.append(" AND (event_time, event_nanos) < (:to, :toNanos)");
            parameters.put("to", databaseTime(filter.to()));
            parameters.put("toNanos", filter.to().getNano());
        }
        if (!filter.status().isEmpty()) {
            sql.append(" AND (payload->>'status')::integer IN (:status)");
            parameters.put("status", filter.status());
        }
        if (filter.statusClass() != null) {
            sql.append(" AND (payload->>'status')::integer BETWEEN :statusMin AND :statusMax");
            int min = (filter.statusClass().charAt(0) - '0') * 100;
            parameters.put("statusMin", min);
            parameters.put("statusMax", min + 99);
        }
        if (cursor != null) {
            sql.append(" AND (event_time, event_nanos, run_id, producer, sequence)")
                    .append(" < (:cursorTime, :cursorNanos, :cursorRun, :cursorProducer, :cursorSequence)");
            parameters.put("cursorTime", databaseTime(cursor.time()));
            parameters.put("cursorNanos", cursor.time().getNano());
            parameters.put("cursorRun", cursor.runId());
            parameters.put("cursorProducer", cursor.producer());
            parameters.put("cursorSequence", cursor.sequence());
        }
        sql.append(" ORDER BY event_time DESC, event_nanos DESC, run_id DESC, producer DESC, sequence DESC LIMIT :limit");
        parameters.put("limit", limit);
        return jdbc.sql(sql.toString()).params(parameters).query((rs, row) -> new EventItem(
                rs.getObject("run_id", UUID.class), EventItem.ProducerEnum.valueOf(rs.getString("producer")),
                mapLog(rs, row))).list();
    }

    private static void filter(StringBuilder sql, Map<String, Object> parameters, String condition, String name, Object value) {
        if (value != null) {
            sql.append(" AND ").append(condition);
            parameters.put(name, value);
        }
    }

    private AccessLog mapLog(ResultSet rs, int row) throws SQLException {
        return json.readValue(rs.getString("payload"), AccessLog.class);
    }

    public void insertLog(UUID id, String producer, AccessLog entry) {
        Instant time = entry.getTime().toInstant();
        jdbc.sql("""
                INSERT INTO detector.access_logs
                    (run_id, producer, sequence, payload, request_id, event_time, event_nanos)
                VALUES (:id, :producer, :sequence, CAST(:payload AS jsonb), :requestId, :time, :nanos)
                """).param("id", id).param("producer", producer).param("sequence", entry.getSequence())
                .param("payload", json.writeValueAsString(entry)).param("requestId", entry.getRequestId())
                .param("time", databaseTime(time)).param("nanos", time.getNano()).update();
    }

    public void advance(UUID id, String producer, int count, int max) {
        jdbc.sql("""
                UPDATE detector.producer_progress
                SET received_count = received_count + :count, max_sequence = GREATEST(max_sequence, :max)
                WHERE run_id = :id AND producer = :producer
                """).param("id", id).param("producer", producer).param("count", count).param("max", max).update();
        jdbc.sql("UPDATE detector.analysis_runs SET input_revision = input_revision + 1 WHERE run_id = :id")
                .param("id", id).update();
    }

    public void finish(UUID id, FinishSpec finish) {
        jdbc.sql("""
                UPDATE detector.producer_progress SET last_sequence =
                    CASE producer WHEN 'GATEWAY' THEN :gateway ELSE :shop END WHERE run_id = :id
                """).param("id", id).param("gateway", finish.gatewayLastSequence())
                .param("shop", finish.shopLastSequence()).update();
        jdbc.sql("""
                UPDATE detector.analysis_runs SET state = 'FINISHING', finish_request = CAST(:finish AS jsonb),
                    deadline_at = :deadline WHERE run_id = :id
                """).param("id", id).param("finish", json.writeValueAsString(finish))
                .param("deadline", databaseTime(finish.deadlineAt())).update();
    }

    public boolean completeRanges(UUID id) {
        return jdbc.sql("""
                SELECT count(*) = 2 AND bool_and(last_sequence IS NOT NULL AND received_count = last_sequence
                    AND max_sequence <= last_sequence) FROM detector.producer_progress WHERE run_id = :id
                """).param("id", id).query(Boolean.class).single();
    }

    public List<UUID> finishingRuns() {
        return jdbc.sql("""
                SELECT run_id FROM detector.analysis_runs WHERE state = 'FINISHING'
                ORDER BY deadline_at, run_id LIMIT 100
                """).query(UUID.class).list();
    }

    public Optional<UUID> nextReady() {
        return jdbc.sql("""
                SELECT r.run_id FROM detector.analysis_runs r
                WHERE r.state IN ('ACCEPTING', 'FINISHING') AND (
                    r.input_revision > r.processed_revision OR
                    r.state = 'FINISHING' AND
                    (SELECT count(*) FROM detector.producer_progress p WHERE p.run_id = r.run_id
                        AND p.received_count = p.last_sequence AND p.max_sequence <= p.last_sequence) = 2)
                ORDER BY r.analyzed_at NULLS FIRST, r.created_at, r.run_id LIMIT 1
                """).query(UUID.class).optional();
    }

    public Set<UUID> observed(UUID id) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT DISTINCT request_id FROM detector.access_logs WHERE run_id = :id
                ORDER BY request_id LIMIT 100001
                """).param("id", id).query(UUID.class).list());
    }

    /** Caller owns a repeatable-read transaction and closes the cursor. */
    public Stream<Observation> observations(UUID id) {
        return jdbc.sql("""
                SELECT producer, sequence, request_id, payload::text AS payload
                FROM detector.access_logs WHERE run_id = :id
                ORDER BY event_time, event_nanos, producer, sequence
                """).withFetchSize(256).param("id", id).query(Observation.Row.class).stream()
                .map(row -> row.toObservation(json));
    }

    public void insertIncident(UUID id, Incident incident) {
        insertIncident(id, incident, false);
    }

    private void insertIncident(UUID id, Incident incident, boolean live) {
        Instant time = incident.getDetectedAt().toInstant();
        jdbc.sql("""
                INSERT INTO %s
                    (run_id, incident_id, detected_at, detected_nanos, family, request_ids, description)
                VALUES (:id, :incidentId, :time, :nanos, :family, :requestIds, :description)
                """.formatted(incidentTable(live))).param("id", id).param("incidentId", incident.getIncidentId())
                .param("time", databaseTime(time)).param("nanos", time.getNano())
                .param("family", incident.getFamily() == null ? null : incident.getFamily().name(), Types.VARCHAR)
                .param("requestIds", new SqlArrayValue("uuid", incident.getRequestIds().toArray()))
                .param("description", incident.getDescription()).update();
    }

    public List<Incident> incidents(UUID id, int limit, int offset, boolean live) {
        return jdbc.sql("""
                SELECT * FROM %s WHERE run_id = :id
                ORDER BY detected_at, detected_nanos, incident_id LIMIT :limit OFFSET :offset
                """.formatted(incidentTable(live))).param("id", id).param("limit", limit).param("offset", offset)
                .query(this::mapIncident).list();
    }

    public long incidentCount(UUID id, boolean live) {
        return jdbc.sql("SELECT count(*) FROM " + incidentTable(live) + " WHERE run_id = :id")
                .param("id", id).query(Long.class).single();
    }

    public List<Incident> liveIncidents(UUID id) {
        return jdbc.sql("SELECT * FROM detector.live_incidents WHERE run_id = :id")
                .param("id", id).query(this::mapIncident).list();
    }

    public void replaceLiveIncidents(UUID id, List<Incident> incidents) {
        jdbc.sql("DELETE FROM detector.live_incidents WHERE run_id = :id").param("id", id).update();
        incidents.forEach(incident -> insertIncident(id, incident, true));
    }

    public void processed(UUID id, long revision) {
        jdbc.sql("""
                UPDATE detector.analysis_runs SET processed_revision = :revision, analyzed_at = clock_timestamp()
                WHERE run_id = :id
                """).param("id", id).param("revision", revision).update();
    }

    private static String incidentTable(boolean live) {
        return live ? "detector.live_incidents" : "detector.incidents";
    }

    public void completed(UUID id) {
        jdbc.sql("UPDATE detector.analysis_runs SET state = 'COMPLETED', completed_at = clock_timestamp() WHERE run_id = :id")
                .param("id", id).update();
    }

    public void failed(UUID id, String error) {
        jdbc.sql("""
                UPDATE detector.analysis_runs SET state = 'FAILED', error = :error
                WHERE run_id = :id AND state IN ('ACCEPTING', 'FINISHING')
                """).param("id", id).param("error", error).update();
    }

    public void interruptPrevious() {
        jdbc.sql("""
                UPDATE detector.analysis_runs SET state = 'FAILED',
                    error = 'Detector restarted before analysis finished; execution was not resumed'
                WHERE state IN ('ACCEPTING', 'FINISHING')
                """).update();
    }

    private Incident mapIncident(ResultSet rs, int row) throws SQLException {
        Instant time = rs.getObject("detected_at", OffsetDateTime.class).toInstant();
        var ids = rs.getArray("request_ids");
        Set<UUID> requests;
        try {
            requests = new LinkedHashSet<>(Arrays.asList((UUID[]) ids.getArray()));
        } finally {
            ids.free();
        }
        var incident = new Incident(rs.getObject("incident_id", UUID.class),
                Instant.ofEpochSecond(time.getEpochSecond(), rs.getInt("detected_nanos")).atOffset(ZoneOffset.UTC),
                requests, rs.getString("description"));
        if (rs.getString("family") != null) {
            incident.family(Incident.FamilyEnum.valueOf(rs.getString("family")));
        }
        return incident;
    }

    private static OffsetDateTime databaseTime(Instant time) {
        return time.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    public record Progress(int count, int max, Integer last) {
    }
}
