package lab.control.trial;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class TrialRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public TrialRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<UUID> lockSlot() {
        return jdbc.sql("SELECT trial_id FROM control.execution_slot WHERE id = 1 FOR UPDATE")
                .query((rs, row) -> Optional.ofNullable(rs.getObject(1, UUID.class))).single();
    }

    public Optional<TrialSnapshot> findRequest(UUID key) {
        return jdbc.sql("SELECT * FROM control.trials WHERE idempotency_key = :key").param("key", key)
                .query(this::map).optional();
    }

    public TrialSnapshot insert(UUID id, List<String> families, int seed, UUID key, Integer requestedSeed) {
        TrialSnapshot trial = jdbc.sql("""
                INSERT INTO control.trials (trial_id, families, seed, idempotency_key, requested_seed, state)
                VALUES (:id, CAST(:families AS jsonb), :seed, :key, :requestedSeed, 'PREPARING') RETURNING *
                """).param("id", id).param("families", json.writeValueAsString(families))
                .param("seed", seed).param("key", key).param("requestedSeed", requestedSeed, Types.INTEGER)
                .query(this::map).single();
        jdbc.sql("UPDATE control.execution_slot SET trial_id = :id WHERE id = 1")
                .param("id", id).update();
        return trial;
    }

    public Optional<TrialSnapshot> find(UUID id) {
        return jdbc.sql("SELECT * FROM control.trials WHERE trial_id = :id")
                .param("id", id).query(this::map).optional();
    }

    public List<TrialSnapshot> list(int limit, int offset) {
        return jdbc.sql("""
                SELECT * FROM control.trials ORDER BY created_at DESC, trial_id DESC
                LIMIT :limit OFFSET :offset
                """).param("limit", limit).param("offset", offset).query(this::map).list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM control.trials").query(Long.class).single();
    }

    public void markInterrupted(UUID id) {
        jdbc.sql("""
                UPDATE control.trials SET state = 'INCONCLUSIVE', cleanup_safe = false,
                    error = 'Control restarted before the trial finished; execution was not resumed'
                WHERE trial_id = :id AND state IN ('PREPARING', 'RUNNING', 'FINALIZING')
                """).param("id", id).update();
    }

    private TrialSnapshot map(ResultSet rs, int row) throws SQLException {
        return new TrialSnapshot(rs.getObject("trial_id", UUID.class),
                Arrays.asList(json.readValue(rs.getString("families"), String[].class)),
                rs.getInt("seed"), rs.getObject("requested_seed", Integer.class), rs.getString("state"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("planned_steps"), rs.getLong("finished_steps"),
                rs.getBoolean("cleanup_safe"), rs.getString("error"));
    }
}
