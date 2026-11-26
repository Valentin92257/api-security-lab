package lab.control.result;

import lab.contracts.json.ContractJsonCodec;
import lab.control.evaluation.BolaAssessment;
import lab.control.runner.GatewayTarget;
import lab.control.runner.OrderEvidence;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

@Repository
public class BolaAssessmentRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public BolaAssessmentRepository(JdbcClient jdbc, ContractJsonCodec codec) {
        this.jdbc = jdbc;
        this.json = codec.jsonMapper();
    }

    public void save(UUID trialId, UUID runId, GatewayTarget target, List<BolaAssessment> assessments) {
        for (BolaAssessment assessment : assessments) {
            jdbc.sql("""
                    INSERT INTO control.bola_assessments (
                        trial_id, run_id, target, kind, order_id, effect, request_id, reason, expected_evidence
                    ) VALUES (
                        :trialId, :runId, :target, :kind, :orderId, :effect, :requestId, :reason, CAST(:expected AS jsonb)
                    )
                    """)
                    .param("trialId", trialId).param("runId", runId).param("target", target.name())
                    .param("kind", assessment.kind().name()).param("orderId", assessment.orderId())
                    .param("effect", assessment.effect().name()).param("requestId", assessment.requestId(), Types.OTHER)
                    .param("reason", assessment.reason())
                    .param("expected", assessment.expectedEvidence() == null ? null
                            : json.writeValueAsString(assessment.expectedEvidence()), Types.VARCHAR)
                    .update();
        }
    }

    public List<BolaAssessment> findByRun(UUID trialId, UUID runId, GatewayTarget target) {
        return jdbc.sql("""
                SELECT * FROM control.bola_assessments
                WHERE trial_id = :trialId AND run_id = :runId AND target = :target
                ORDER BY CASE kind WHEN 'VICTIM_ORDER' THEN 0 WHEN 'OWN_ORDER' THEN 1 ELSE 2 END, order_id
                """)
                .param("trialId", trialId).param("runId", runId).param("target", target.name())
                .query((rs, row) -> new BolaAssessment(BolaAssessment.CaseKind.valueOf(rs.getString("kind")),
                        rs.getLong("order_id"), BolaAssessment.Effect.valueOf(rs.getString("effect")),
                        rs.getObject("request_id", UUID.class), rs.getString("reason"),
                        rs.getString("expected_evidence") == null ? null
                                : json.readValue(rs.getString("expected_evidence"), OrderEvidence.class)))
                .list();
    }
}
