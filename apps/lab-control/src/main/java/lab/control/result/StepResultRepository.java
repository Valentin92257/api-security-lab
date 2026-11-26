package lab.control.result;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lab.contracts.json.ContractJsonCodec;
import lab.control.runner.OrderEvidence;
import lab.control.runner.GatewayTarget;
import lab.control.runner.StepOutcome;
import lab.control.runner.StepResult;
import lab.control.runner.StepResultSink;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class StepResultRepository implements StepResultSink {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public StepResultRepository(JdbcClient jdbc, ContractJsonCodec codec) {
        this.jdbc = jdbc;
        this.json = codec.jsonMapper();
    }

    @Override
    public void save(StepResult result) {
        jdbc.sql("""
                INSERT INTO control.step_results (
                    trial_id, run_id, request_id, target, method, route, status, outcome,
                    started_at, duration_millis, order_evidence
                ) VALUES (
                    :trialId, :runId, :requestId, :target, :method, :route, :status, :outcome,
                    :startedAt, :durationMillis, CAST(:evidence AS jsonb)
                )
                """).param("trialId", result.trialId()).param("runId", result.runId())
                .param("requestId", result.requestId()).param("target", result.target().name())
                .param("method", result.method()).param("route", result.route())
                .param("status", result.status(), Types.INTEGER).param("outcome", result.outcome().name())
                .param("startedAt", result.startedAt()).param("durationMillis", result.durationMillis())
                .param("evidence", result.orderEvidence() == null ? null : json.writeValueAsString(result.orderEvidence()),
                        Types.VARCHAR).update();
    }

    public Optional<StepResult> find(UUID requestId) {
        return jdbc.sql("SELECT * FROM control.step_results WHERE request_id = :requestId")
                .param("requestId", requestId).query(this::map).optional();
    }

    public List<StepResult> findByRun(UUID trialId, UUID runId, GatewayTarget target) {
        return jdbc.sql("""
                SELECT * FROM control.step_results
                WHERE trial_id = :trialId AND run_id = :runId AND target = :target
                ORDER BY started_at, request_id
                """)
                .param("trialId", trialId).param("runId", runId).param("target", target.name())
                .query(this::map).list();
    }

    private StepResult map(ResultSet rs, int row) throws SQLException {
        return new StepResult(rs.getObject("trial_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("request_id", UUID.class), GatewayTarget.valueOf(rs.getString("target")),
                rs.getString("method"), rs.getString("route"), rs.getObject("status", Integer.class),
                StepOutcome.valueOf(rs.getString("outcome")), rs.getObject("started_at", OffsetDateTime.class),
                rs.getLong("duration_millis"),
                rs.getString("order_evidence") == null ? null
                        : json.readValue(rs.getString("order_evidence"), OrderEvidence.class));
    }
}
