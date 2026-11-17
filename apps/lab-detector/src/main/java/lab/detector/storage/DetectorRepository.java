package lab.detector.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import lab.contracts.detector.model.AccessLog;
import lab.contracts.detector.model.Incident;
import lab.contracts.json.ContractJsonCodec;
import lab.detector.analysis.FinishSpec;
import lab.detector.analysis.RunSnapshot;
import lab.detector.engine.AnalysisEngine.Observation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DetectorRepository {
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final JsonMapper json;

    public DetectorRepository(JdbcClient jdbc, JdbcTemplate template, ContractJsonCodec codec) {
        this.jdbc = jdbc;
        this.template = template;
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
        return jdbc.sql("SELECT * FROM detector.analysis_runs WHERE run_id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query((rs, row) -> new RunSnapshot(id, rs.getString("state"), rs.getString("error"),
                        rs.getString("finish_request") == null ? null
                                : FinishSpec.from(json.readTree(rs.getString("finish_request"))))).optional();
    }

    public Progress progress(UUID id, String producer) {
        return jdbc.sql("SELECT * FROM detector.producer_progress WHERE run_id = :id AND producer = :producer")
                .param("id", id).param("producer", producer)
                .query((rs, row) -> new Progress(rs.getInt("received_count"), rs.getInt("max_sequence"),
                        rs.getObject("last_sequence", Integer.class))).single();
    }

    public Optional<AccessLog> findLog(UUID id, String producer, int sequence) {
        return jdbc.sql("""
                SELECT payload FROM detector.access_logs
                WHERE run_id = :id AND producer = :producer AND sequence = :sequence
                """).param("id", id).param("producer", producer).param("sequence", sequence)
                .query((rs, row) -> json.readValue(rs.getString("payload"), AccessLog.class)).optional();
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
                SELECT r.run_id FROM detector.analysis_runs r WHERE r.state = 'FINISHING'
                AND (SELECT count(*) FROM detector.producer_progress p WHERE p.run_id = r.run_id
                    AND p.received_count = p.last_sequence AND p.max_sequence <= p.last_sequence) = 2
                ORDER BY r.deadline_at, r.run_id LIMIT 1
                """).query(UUID.class).optional();
    }

    public Set<UUID> observed(UUID id) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT DISTINCT request_id FROM detector.access_logs WHERE run_id = :id
                ORDER BY request_id LIMIT 100001
                """).param("id", id).query(UUID.class).list());
    }

    /** Caller owns a read transaction and closes the cursor; the complete run is immutable. */
    public Stream<Observation> observations(UUID id) {
        return template.queryForStream(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT producer, sequence, request_id, payload FROM detector.access_logs WHERE run_id = ?
                    ORDER BY event_time, event_nanos, producer, sequence
                    """);
            statement.setObject(1, id);
            statement.setFetchSize(256);
            return statement;
        }, (rs, row) -> {
            JsonNode payload = json.readTree(rs.getString("payload"));
            return new Observation(rs.getString("producer"), rs.getInt("sequence"),
                    rs.getObject("request_id", UUID.class),
                    OffsetDateTime.parse(payload.get("time").asString()).toInstant(), payload);
        });
    }

    public void insertIncident(UUID id, Incident incident) {
        Instant time = incident.getDetectedAt().toInstant();
        template.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO detector.incidents
                        (run_id, incident_id, detected_at, detected_nanos, family, request_ids, description)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setObject(1, id);
            statement.setObject(2, incident.getIncidentId());
            statement.setObject(3, databaseTime(time));
            statement.setInt(4, time.getNano());
            statement.setObject(5, incident.getFamily() == null ? null : incident.getFamily().name(), Types.VARCHAR);
            statement.setArray(6, connection.createArrayOf("uuid", incident.getRequestIds().toArray(UUID[]::new)));
            statement.setString(7, incident.getDescription());
            return statement;
        });
    }

    public List<Incident> incidents(UUID id, int limit, int offset) {
        return jdbc.sql("""
                SELECT * FROM detector.incidents WHERE run_id = :id
                ORDER BY detected_at, detected_nanos, incident_id LIMIT :limit OFFSET :offset
                """).param("id", id).param("limit", limit).param("offset", offset).query(this::mapIncident).list();
    }

    public long incidentCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM detector.incidents WHERE run_id = :id")
                .param("id", id).query(Long.class).single();
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
