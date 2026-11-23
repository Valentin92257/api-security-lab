package lab.detector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import lab.contracts.detector.model.Incident;
import lab.detector.engine.AnalysisEngine;

final class DetectorTestEngine implements AnalysisEngine {
    final AtomicInteger calls = new AtomicInteger();
    volatile List<Incident> results = List.of();
    final Map<UUID, List<Incident>> resultsByRun = new ConcurrentHashMap<>();
    volatile List<Observation> observations = List.of();
    volatile boolean block;
    volatile boolean throwError;
    volatile String failureMessage = "Test engine failure";
    volatile CountDownLatch entered = new CountDownLatch(1);
    volatile CountDownLatch release = new CountDownLatch(1);
    volatile CountDownLatch exited = new CountDownLatch(1);

    void reset() {
        calls.set(0);
        results = List.of();
        resultsByRun.clear();
        observations = List.of();
        block = false;
        throwError = false;
        failureMessage = "Test engine failure";
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        exited = new CountDownLatch(1);
    }

    @Override
    public List<Incident> analyze(UUID id, Stream<Observation> logs) {
        calls.incrementAndGet();
        observations = logs.toList();
        List<Incident> snapshot = resultsByRun.getOrDefault(id, results);
        boolean wait = block;
        entered.countDown();
        try {
            if (wait && !release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Engine test latch timed out");
            }
            if (throwError) {
                throw new IllegalStateException(failureMessage);
            }
            return snapshot;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        } finally {
            exited.countDown();
        }
    }
}
