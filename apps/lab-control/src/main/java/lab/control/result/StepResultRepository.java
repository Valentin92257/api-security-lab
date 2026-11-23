package lab.control.result;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import lab.control.runner.GatewayTarget;
import lab.control.runner.StepOutcome;
import lab.control.runner.StepResult;
import lab.control.runner.StepResultSink;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StepResultRepository implements StepResultSink {
    private final JdbcClient jdbc;

    public StepResultRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(StepResult result) {
        jdbc.sql("""
                INSERT INTO control.step_results (
                    trial_id, run_id, request_id, target, method, route, status, outcome, started_at, duration_millis
                ) VALUES (
                    :trialId, :runId, :requestId, :target, :method, :route, :status, :outcome, :startedAt, :durationMillis
                )
                """).param("trialId", result.trialId()).param("runId", result.runId())
                .param("requestId", result.requestId()).param("target", result.target().name())
                .param("method", result.method()).param("route", result.route())
                .param("status", result.status(), Types.INTEGER).param("outcome", result.outcome().name())
                .param("startedAt", result.startedAt()).param("durationMillis", result.durationMillis()).update();
    }

    public Optional<StepResult> find(UUID requestId) {
        return jdbc.sql("SELECT * FROM control.step_results WHERE request_id = :requestId")
                .param("requestId", requestId).query(this::map).optional();
    }

    private StepResult map(ResultSet rs, int row) throws SQLException {
        return new StepResult(rs.getObject("trial_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("request_id", UUID.class), GatewayTarget.valueOf(rs.getString("target")),
                rs.getString("method"), rs.getString("route"), rs.getObject("status", Integer.class),
                StepOutcome.valueOf(rs.getString("outcome")), rs.getObject("started_at", OffsetDateTime.class),
                rs.getLong("duration_millis"));
    }
}
