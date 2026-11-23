package lab.control.runner;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ScenarioRunner implements AutoCloseable {
    private final Duration timeout;
    private final long timeoutNanos;
    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor deadlines;
    private volatile Thread worker;
    private RunContext active;
    private boolean closed;

    public ScenarioRunner(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout);
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Run timeout is too large");
        }
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("Run timeout must be positive");
        }
        var factory = Thread.ofPlatform().name("runner-", 0).daemon(true).factory();
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
            worker = factory.newThread(task);
            return worker;
        }, new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("runner-deadline-", 0).daemon(true).factory());
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public synchronized RunHandle start(UUID trialId, UUID runId, Consumer<RunContext> scenario) {
        Objects.requireNonNull(scenario);
        if (closed) {
            throw new RejectedExecutionException("Runner is closed");
        }
        if (active != null) {
            throw new RejectedExecutionException("Runner already has an active run");
        }
        var context = new RunContext(trialId, runId, timeout);
        var completion = new CompletableFuture<RunResult>();
        var handle = new RunHandle(context, completion);
        ScheduledFuture<?> alarm = deadlines.schedule(context::timeout, timeoutNanos, TimeUnit.NANOSECONDS);
        active = context;
        try {
            executor.submit(() -> execute(context, scenario, completion, alarm));
        } catch (RuntimeException error) {
            active = null;
            alarm.cancel(false);
            context.cancel();
            throw error;
        }
        return handle;
    }

    private void execute(RunContext context, Consumer<RunContext> scenario,
                         CompletableFuture<RunResult> completion, ScheduledFuture<?> alarm) {
        boolean returned = false;
        try {
            if (!context.isStopped()) {
                scenario.accept(context);
            }
            returned = true;
        } catch (CancellationException error) {
            context.cancel();
            returned = true;
        } finally {
            if (!returned) {
                context.fail();
            }
            RunResult result = context.finish();
            alarm.cancel(false);
            synchronized (this) {
                if (active == context) {
                    active = null;
                }
            }
            completion.complete(result);
        }
    }

    @Override
    public void close() {
        RunContext stopping;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            stopping = active;
            executor.shutdown();
            deadlines.shutdown();
        }
        if (stopping != null) {
            stopping.cancel();
        }
        if (Thread.currentThread() != worker) {
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
