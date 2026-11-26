package lab.control;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lab.control.evaluation.BolaEvaluator;
import lab.control.result.BolaAssessmentRepository;
import lab.control.result.StepResultRepository;
import lab.control.trial.TrialRepository;
import lab.control.trial.TrialSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "lab.control.operator-token=json-errors-test-token",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
class ControlJsonErrorsTest {
    @MockitoBean
    private TrialRepository repository;

    @MockitoBean
    private StepResultRepository stepResults;

    @MockitoBean
    private BolaAssessmentRepository bolaAssessments;

    @MockitoBean
    private BolaEvaluator bolaEvaluator;

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void closeClient() {
        client.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"families\":[\"BOLA\",\"BOLA\"]}",
            "{\"families\":[\"BOLA\"],\"seed\":\"42\"}",
            "{"
    })
    void returns400ForInvalidRequestJson(String json) throws Exception {
        var response = client.send(request("/trials")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(response, 400, "INVALID_REQUEST");
    }

    @Test
    void returns500ForInvalidContractResponse() throws Exception {
        UUID trialId = UUID.randomUUID();
        var invalidTrial = new TrialSnapshot(trialId, List.of("BOLA"), -1, null, "PREPARING",
                OffsetDateTime.parse("2026-10-23T12:00:00Z"), 0, 0, false, null);
        when(repository.find(trialId)).thenReturn(Optional.of(invalidTrial));

        var response = client.send(request("/trials/" + trialId).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertProblem(response, 500, "INTERNAL_ERROR");
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/control/v1" + path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer json-errors-test-token");
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var problem = JsonMapper.builder().build().readTree(response.body());
        assertThat(problem.get("type").asString()).isEqualTo("urn:lab:problem:http-error");
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("code").asString()).isEqualTo(code);
        assertThat(UUID.fromString(problem.get("requestId").asString())).isNotNull();
        assertThat(response.body()).doesNotContain("Exception", "seed", "must have");
    }
}
