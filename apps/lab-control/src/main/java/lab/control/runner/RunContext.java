package lab.control.runner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class RunContext {
    static final int MAX_STEPS = 400;

    private final UUID trialId;
    private final UUID runId;
    private final long deadline;
    private final List<StepResult> steps = new ArrayList<>();
    private RunOutcome outcome;
    private CompletableFuture<?> pending;
    private CompletableFuture<Void> drained;
    private int startedSteps;
    private boolean finishing;
    private boolean finished;

    public RunContext(UUID trialId, UUID runId, Duration timeout) {
        this.trialId = Objects.requireNonNull(trialId);
        this.runId = Objects.requireNonNull(runId);
        Objects.requireNonNull(timeout);
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Run timeout is too large");
        }
        if (nanos <= 0) {
            throw new IllegalArgumentException("Run timeout must be positive");
        }
        deadline = System.nanoTime() + nanos;
    }

    public UUID trialId() {
        return trialId;
    }

    public UUID runId() {
        return runId;
    }

    long remainingNanos() {
        return Math.max(0, deadline - System.nanoTime());
    }

    public boolean isStopped() {
        if (remainingNanos() == 0) {
            timeout();
        }
        if (Thread.currentThread().isInterrupted()) {
            cancel();
        }
        synchronized (this) {
            return outcome != null || finishing || finished;
        }
    }

    synchronized <T> CompletableFuture<T> dispatch(Supplier<CompletableFuture<T>> send) {
        if (isStopped()) {
            throw new CancellationException("Run is stopped");
        }
        if (pending != null) {
            throw new IllegalStateException("A step is already running");
        }
        if (startedSteps == MAX_STEPS) {
            fail();
            throw new IllegalStateException("Run step budget exceeded");
        }
        startedSteps++;
        CompletableFuture<T> future;
        try {
            future = Objects.requireNonNull(send.get());
        } catch (RuntimeException | Error error) {
            fail();
            throw error;
        }
        pending = future;
        drained = new CompletableFuture<>();
        return future;
    }

    void release(CompletableFuture<?> future) {
        CompletableFuture<Void> completed;
        synchronized (this) {
            if (pending != future) {
                throw new IllegalStateException("Step does not belong to this run");
            }
            pending = null;
            completed = drained;
            drained = null;
        }
        completed.complete(null);
    }

    synchronized void record(StepResult result) {
        if (finished || steps.size() == MAX_STEPS) {
            throw new IllegalStateException("Run cannot accept more results");
        }
        steps.add(result);
    }

    boolean cancel() {
        return stop(RunOutcome.CANCELLED);
    }

    boolean timeout() {
        return stop(RunOutcome.TIMED_OUT);
    }

    void fail() {
        stop(RunOutcome.FAILED);
    }

    private boolean stop(RunOutcome reason) {
        CompletableFuture<?> request;
        synchronized (this) {
            if (finished || outcome != null && reason != RunOutcome.FAILED) {
                return false;
            }
            outcome = reason;
            request = pending;
        }
        if (request != null) {
            request.cancel(true);
        }
        return true;
    }

    synchronized boolean timedOut() {
        return outcome == RunOutcome.TIMED_OUT;
    }

    RunResult finish() {
        isStopped();
        CompletableFuture<Void> remaining;
        synchronized (this) {
            finishing = true;
            remaining = drained;
        }
        if (remaining != null) {
            remaining.join();
        }
        isStopped();
        synchronized (this) {
            if (startedSteps != steps.size()) {
                outcome = RunOutcome.FAILED;
            }
            finished = true;
            return new RunResult(trialId, runId, outcome == null ? RunOutcome.COMPLETED : outcome,
                    startedSteps, steps);
        }
    }
}
