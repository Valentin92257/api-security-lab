package lab.detector.finalization;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lab.contracts.detector.model.Incident;
import lab.detector.analysis.AnalysisService;
import lab.detector.engine.AnalysisEngine;
import lab.detector.storage.DetectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "lab.detector.worker-enabled", havingValue = "true", matchIfMissing = true)
public class FinalizationWorker implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(FinalizationWorker.class);
    private final DetectorRepository repository;
    private final AnalysisService analyses;
    private final AnalysisEngine engine;
    private final TransactionTemplate readTransaction;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("detector-analysis").factory());

    public FinalizationWorker(DetectorRepository repository, AnalysisService analyses, AnalysisEngine engine,
                              PlatformTransactionManager transactions) {
        this.repository = repository;
        this.analyses = analyses;
        this.engine = engine;
        readTransaction = new TransactionTemplate(transactions);
        readTransaction.setReadOnly(true);
    }

    @Scheduled(fixedDelayString = "${lab.detector.poll-interval-ms:100}")
    public void tick() {
        boolean claimed = false;
        try {
            // Watchdog remains independent of a slow engine running in the other executor.
            for (UUID id : repository.finishingRuns()) {
                analyses.expire(id);
            }
            if (!busy.compareAndSet(false, true)) {
                return;
            }
            claimed = true;
            var next = repository.nextReady();
            if (next.isEmpty()) {
                busy.set(false);
                return;
            }
            executor.execute(() -> process(next.get()));
        } catch (RuntimeException error) {
            if (claimed) {
                busy.set(false);
            }
            LOG.error("Detector scheduling failed: {}", error.getClass().getSimpleName());
        }
    }

    private void process(UUID id) {
        try {
            if (!analyses.ready(id)) {
                return;
            }
            List<Incident> incidents = readTransaction.execute(status -> {
                try (var observations = repository.observations(id)) {
                    return List.copyOf(engine.analyze(id, observations));
                }
            });
            analyses.publish(id, incidents);
        } catch (RuntimeException error) {
            LOG.error("Detector analysis failed for run {}: {}", id, error.getClass().getSimpleName());
            try {
                analyses.fail(id, "Analysis failed; the run must be repeated");
            } catch (RuntimeException unavailable) {
                LOG.error("Cannot persist analysis failure for run {}: {}", id, unavailable.getClass().getSimpleName());
            }
        } finally {
            busy.set(false);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
