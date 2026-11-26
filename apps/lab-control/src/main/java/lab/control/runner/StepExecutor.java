package lab.control.runner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import lab.contracts.json.ContractValidationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class StepExecutor {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> HEADERS = Set.of(
            "authorization", "content-type", "idempotency-key", "x-forwarded-for");

    private final HttpClient client;
    private final StepResultSink results;
    private final ShopContractValidator contracts;

    public StepExecutor(@Qualifier("runnerClient") HttpClient client, StepResultSink results,
                        ShopContractValidator contracts) {
        this.client = client;
        this.results = results;
        this.contracts = contracts;
    }

    public StepResponse execute(RunContext run, StepRequest step) {
        Objects.requireNonNull(run);
        if (run.isStopped()) {
            throw new CancellationException("Run is stopped");
        }
        URI route = ShopRoute.parse(step.route());
        ShopRoute operation = ShopRoute.match(step.method(), route);
        UUID requestId = UUID.randomUUID();
        var builder = HttpRequest.newBuilder(step.target().resolve(route))
                .timeout(TIMEOUT)
                .header("X-Request-Id", requestId.toString())
                .header("Accept", "application/json");
        Set<String> names = new HashSet<>();
        step.headers().forEach((name, value) -> {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!HEADERS.contains(normalized) || !names.add(normalized)) {
                throw new IllegalArgumentException("Unsupported step header");
            }
            try {
                builder.header(name, value);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid step header");
            }
        });
        var body = step.body() == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(step.body());
        HttpRequest request = builder.method(step.method(), body).build();
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        long started = System.nanoTime();
        Integer status = null;
        StepOutcome outcome;
        JsonNode responseBody = null;
        boolean interrupted = false;
        var pending = run.dispatch(() -> client.sendAsync(request, HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES)));
        try {
            try {
                var response = pending.get(Math.min(TIMEOUT.toNanos(), run.remainingNanos()), TimeUnit.NANOSECONDS);
                status = response.statusCode();
                outcome = status >= 300 && status < 400 ? StepOutcome.REDIRECT_REJECTED : StepOutcome.RESPONSE;
                if (outcome == StepOutcome.RESPONSE) {
                    try {
                        responseBody = contracts.validateResponse(operation, status, response.headers(),
                                response.body(), requestId);
                    } catch (ContractValidationException error) {
                        outcome = StepOutcome.CONTRACT_VIOLATION;
                    }
                }
            } catch (TimeoutException error) {
                pending.cancel(true);
                if (run.remainingNanos() == 0) {
                    run.timeout();
                }
                outcome = StepOutcome.TIMEOUT;
            } catch (CancellationException error) {
                if (!run.isStopped()) {
                    run.cancel();
                }
                outcome = run.timedOut() ? StepOutcome.TIMEOUT : StepOutcome.INTERRUPTED;
            } catch (ExecutionException error) {
                outcome = error.getCause() instanceof HttpTimeoutException ? StepOutcome.TIMEOUT : StepOutcome.IO_ERROR;
            } catch (InterruptedException error) {
                run.cancel();
                outcome = StepOutcome.INTERRUPTED;
                interrupted = true;
            }
            var result = new StepResult(run.trialId(), run.runId(), requestId, step.target(), step.method(),
                    route.getRawPath(), status, outcome, startedAt,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    operation == ShopRoute.ORDER && outcome == StepOutcome.RESPONSE && Integer.valueOf(200).equals(status)
                            ? OrderEvidence.from(responseBody) : null);
            results.save(result);
            run.record(result);
            return new StepResponse(result, responseBody);
        } catch (RuntimeException | Error error) {
            run.fail();
            throw error;
        } finally {
            run.release(pending);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
