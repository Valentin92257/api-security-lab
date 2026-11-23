package lab.control.runner;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(10)
class ScenarioRunnerTest {
    private final HttpClient client = mock(HttpClient.class);
    private final ShopContractValidator contracts = mock(ShopContractValidator.class);
    private final List<StepResult> saved = Collections.synchronizedList(new ArrayList<>());
    private final StepExecutor steps = new StepExecutor(client, saved::add, contracts);
    private final StepRequest request = new StepRequest(GatewayTarget.PROTECTED, "GET", "/api/v1/products", Map.of(), null);
    private final UUID trialId = UUID.randomUUID();

    @Test
    void executesOnADedicatedThreadAndCanRunAgainAfterCompletion() throws Exception {
        respondsImmediately();
        var caller = Thread.currentThread();
        var worker = new AtomicReference<Thread>();
        try (var runner = new ScenarioRunner(Duration.ofSeconds(5))) {
            var first = start(runner, context -> {
                worker.set(Thread.currentThread());
                steps.execute(context, request);
            });

            var completed = result(first);
            var second = result(start(runner, context -> steps.execute(context, request)));

            assertThat(worker.get()).isNotSameAs(caller);
            assertThat(completed.outcome()).isEqualTo(RunOutcome.COMPLETED);
            assertThat(completed.trialId()).isEqualTo(trialId);
            assertThat(completed.startedSteps()).isEqualTo(1);
            assertThat(completed.steps()).containsExactly(saved.getFirst());
            assertThat(second.outcome()).isEqualTo(RunOutcome.COMPLETED);
            assertThat(second.runId()).isNotEqualTo(completed.runId());
            assertThat(first.cancel()).isFalse();
        }
    }

    @Test
    void startsTheNextRunFromACompletionContinuation() throws Exception {
        respondsImmediately();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var continuation = new AtomicReference<Thread>();
        try (var runner = new ScenarioRunner(Duration.ofSeconds(5))) {
            try {
                var first = start(runner, context -> {
                    worker.set(Thread.currentThread());
                    entered.countDown();
                    await(release);
                    steps.execute(context, request);
                });
                await(entered);
                var next = first.completion().thenCompose(completed -> {
                    continuation.set(Thread.currentThread());
                    return start(runner, context -> steps.execute(context, request)).completion();
                }).toCompletableFuture();
                release.countDown();

                assertThat(next.get(3, TimeUnit.SECONDS).outcome()).isEqualTo(RunOutcome.COMPLETED);
                assertThat(continuation.get()).isSameAs(worker.get());
                verify(client, times(2)).sendAsync(any(HttpRequest.class), any());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rejectsAnotherRunAndWaitsForPreparationToStopAfterCancellation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var runner = new ScenarioRunner(Duration.ofSeconds(5))) {
            try {
                var handle = start(runner, context -> {
                    entered.countDown();
                    await(release);
                    if (!context.isStopped()) {
                        steps.execute(context, request);
                    }
                });
                await(entered);

                assertThatThrownBy(() -> start(runner, context -> {}))
                        .isInstanceOf(RejectedExecutionException.class);
                assertThat(handle.cancel()).isTrue();
                assertThat(handle.cancel()).isFalse();
                assertThat(handle.completion().toCompletableFuture()).isNotDone();
                release.countDown();

                var completed = result(handle);
                assertThat(completed.outcome()).isEqualTo(RunOutcome.CANCELLED);
                assertThat(completed.startedSteps()).isZero();
                assertThat(completed.steps()).isEmpty();
                verifyNoInteractions(client);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void cancelsAnActiveHttpRequestAndSavesItsOutcomeBeforeCompletion() throws Exception {
        var sent = new CountDownLatch(1);
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        when(client.<byte[]>sendAsync(any(), any())).thenAnswer(call -> {
            sent.countDown();
            return pending;
        });
        try (var runner = new ScenarioRunner(Duration.ofSeconds(5))) {
            var handle = start(runner, this::sendTwoSteps);
            await(sent);

            assertThat(handle.cancel()).isTrue();
            var completed = result(handle);

            assertThat(pending.isCancelled()).isTrue();
            assertThat(completed.outcome()).isEqualTo(RunOutcome.CANCELLED);
            assertThat(completed.startedSteps()).isEqualTo(1);
            assertThat(completed.steps()).containsExactlyElementsOf(saved);
            assertThat(saved).singleElement().satisfies(step -> {
                assertThat(step.outcome()).isEqualTo(StepOutcome.INTERRUPTED);
                assertThat(step.status()).isNull();
            });
            verify(client).sendAsync(any(HttpRequest.class), any());
        }
    }

    @Test
    void appliesTheRunDeadlineToAnActiveHttpRequest() throws Exception {
        var sent = new CountDownLatch(1);
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        when(client.<byte[]>sendAsync(any(), any())).thenAnswer(call -> {
            sent.countDown();
            return pending;
        });
        try (var runner = new ScenarioRunner(Duration.ofSeconds(1))) {
            var handle = start(runner, this::sendTwoSteps);
            await(sent);

            var completed = result(handle);

            assertThat(pending.isCancelled()).isTrue();
            assertThat(completed.outcome()).isEqualTo(RunOutcome.TIMED_OUT);
            assertThat(completed.startedSteps()).isEqualTo(1);
            assertThat(completed.steps()).singleElement()
                    .satisfies(step -> assertThat(step.outcome()).isEqualTo(StepOutcome.TIMEOUT));
            verify(client).sendAsync(any(HttpRequest.class), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitsForStorageWithoutInterruptingItWhenCancelledOrExpired(boolean deadline) throws Exception {
        respondsImmediately();
        var saving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var executor = new StepExecutor(client, step -> {
            saving.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException error) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Storage interrupted");
            }
            saved.add(step);
        }, contracts);
        try (var runner = new ScenarioRunner(Duration.ofSeconds(deadline ? 1 : 5))) {
            try {
                var handle = start(runner, context -> {
                    executor.execute(context, request);
                    if (!context.isStopped()) {
                        executor.execute(context, request);
                    }
                });
                await(saving);
                if (deadline) {
                    assertThatThrownBy(() -> handle.completion().toCompletableFuture().get(1200, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                } else {
                    assertThat(handle.cancel()).isTrue();
                    assertThat(handle.completion().toCompletableFuture()).isNotDone();
                }

                assertThatThrownBy(() -> start(runner, context -> {}))
                        .isInstanceOf(RejectedExecutionException.class);
                assertThat(interrupted).isFalse();
                release.countDown();
                var completed = result(handle);

                assertThat(completed.outcome()).isEqualTo(deadline ? RunOutcome.TIMED_OUT : RunOutcome.CANCELLED);
                assertThat(completed.startedSteps()).isEqualTo(1);
                assertThat(completed.steps()).containsExactlyElementsOf(saved).hasSize(1);
                assertThat(interrupted).isFalse();
                verify(client).sendAsync(any(HttpRequest.class), any());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void reportsIncompleteResultsAndStopsNewStepsAfterStorageFailure() throws Exception {
        respondsImmediately();
        var executor = new StepExecutor(client, step -> {
            throw new IllegalStateException("private-password");
        }, contracts);
        try (var runner = new ScenarioRunner(Duration.ofSeconds(5))) {
            var handle = start(runner, context -> {
                try {
                    executor.execute(context, request);
                } catch (IllegalStateException ignored) {
                }
                executor.execute(context, request);
            });

            var completed = result(handle);

            assertThat(completed.outcome()).isEqualTo(RunOutcome.FAILED);
            assertThat(completed.startedSteps()).isEqualTo(1);
            assertThat(completed.steps()).isEmpty();
            assertThat(completed.toString()).doesNotContain("private-password");
            verify(client).sendAsync(any(HttpRequest.class), any());
        }
    }

    @Test
    void stopsNewStepsWhenTheHttpClientThrowsBeforeReturningAFuture() throws Exception {
        when(client.<byte[]>sendAsync(any(), any())).thenThrow(new IllegalStateException("private-secret"));
        try (var runner = new ScenarioRunner(Duration.ofSeconds(5))) {
            var completed = result(start(runner, context -> {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        steps.execute(context, request);
                    } catch (IllegalStateException ignored) {
                    }
                }
            }));

            assertThat(completed.outcome()).isEqualTo(RunOutcome.FAILED);
            assertThat(completed.startedSteps()).isEqualTo(1);
            assertThat(completed.steps()).isEmpty();
            assertThat(completed.toString()).doesNotContain("private-secret");
            verify(client).sendAsync(any(HttpRequest.class), any());
        }
    }

    @Test
    void cancellingACompletionViewDoesNotCancelTheRun() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var context = new AtomicReference<RunContext>();
        try (var runner = new ScenarioRunner(Duration.ofSeconds(5))) {
            try {
                var handle = start(runner, run -> {
                    context.set(run);
                    entered.countDown();
                    await(release);
                });
                await(entered);

                assertThat(handle.completion().toCompletableFuture().cancel(true)).isTrue();
                assertThat(context.get().isStopped()).isFalse();
                assertThat(handle.completion().toCompletableFuture()).isNotDone();
                release.countDown();

                assertThat(result(handle).outcome()).isEqualTo(RunOutcome.COMPLETED);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void enforcesTheStepBudgetBeforeAnotherHttpRequest() throws Exception {
        respondsImmediately();
        try (var runner = new ScenarioRunner(Duration.ofSeconds(5))) {
            var completed = result(start(runner, context -> {
                for (int attempt = 0; attempt < 401; attempt++) {
                    steps.execute(context, request);
                }
            }));

            assertThat(completed.outcome()).isEqualTo(RunOutcome.FAILED);
            assertThat(completed.startedSteps()).isEqualTo(400);
            assertThat(completed.steps()).hasSize(400);
            verify(client, times(400)).sendAsync(any(HttpRequest.class), any());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidRunTimeout(long seconds) {
        assertThatThrownBy(() -> new ScenarioRunner(Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void sendTwoSteps(RunContext context) {
        steps.execute(context, request);
        if (!context.isStopped()) {
            steps.execute(context, request);
        }
    }

    private RunHandle start(ScenarioRunner runner, Consumer<RunContext> action) {
        return runner.start(trialId, UUID.randomUUID(), action);
    }

    private RunResult result(RunHandle handle) throws Exception {
        return handle.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private void respondsImmediately() {
        HttpResponse<byte[]> response = mock();
        when(response.statusCode()).thenReturn(200);
        when(client.<byte[]>sendAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(response));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unexpected interruption");
        }
    }
}
