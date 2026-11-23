package lab.detector;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static lab.detector.DetectorTestData.batch;
import static lab.detector.DetectorTestData.log;
import static lab.detector.DetectorTestData.path;
import static org.assertj.core.api.Assertions.assertThat;

class DetectorRunsIntegrationTest extends AbstractDetectorApiIntegrationTest {
    @Test
    void preparesOnceAndKeepsStatusPrivateUntilCompletion() throws Exception {
        UUID id = UUID.randomUUID();
        var created = control("PUT", path(id), null);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(body(created).get("state").asString()).isEqualTo("ACCEPTING");
        assertThat(body(created).get("observedRequestIds").isNull()).isTrue();
        var repeated = control("PUT", path(id), null);
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(body(repeated)).isEqualTo(body(created));
        assertThat(body(control("GET", path(id), null))).isEqualTo(body(created));
        assertThat(count("producer_progress", id)).isEqualTo(2);
        var incidents = control("GET", path(id) + "/incidents", null);
        assertThat(incidents.statusCode()).isEqualTo(200);
        assertThat(body(incidents).get("items")).isEmpty();
        assertThat(engine.calls.get()).isZero();
    }

    @Test
    void concurrentPrepareCreatesExactlyOneRun() throws Exception {
        UUID id = UUID.randomUUID();
        var responses = concurrent(Collections.nCopies(8, () -> control("PUT", path(id), null)));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList())
                .containsOnly(200, 201).filteredOn(code -> code == 201).hasSize(1);
        assertThat(responses.stream().map(response -> body(response).get("instanceId")).distinct().count()).isEqualTo(1);
        assertThat(count("analysis_runs", id)).isEqualTo(1);
        assertThat(count("producer_progress", id)).isEqualTo(2);
    }

    @Test
    void frameworkErrorsUseProblemAndUnsupportedAcceptDoesNotCreateRun() throws Exception {
        UUID id = UUID.randomUUID();
        assertProblem(control("DELETE", path(id), null), 405, "INVALID_REQUEST");
        assertProblem(control("GET", "/missing", null), 404, "NOT_FOUND");
        var unsupported = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1" + path(id)))
                .header("Authorization", "Bearer control-test").header("Accept", "application/problem+json")
                .PUT(HttpRequest.BodyPublishers.noBody()).build();
        assertProblem(http.send(unsupported, HttpResponse.BodyHandlers.ofString()), 406, "INVALID_REQUEST");
        assertThat(count("analysis_runs", id)).isZero();
        UUID prepared = prepare();
        var text = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1" + path(prepared) + "/log-batches"))
                .header("Authorization", "Bearer gateway-test").header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(batch("GATEWAY", log(1, id)))).build();
        assertProblem(http.send(text, HttpResponse.BodyHandlers.ofString()), 415, "INVALID_REQUEST");
        assertThat(count("access_logs", prepared)).isZero();
    }
}
