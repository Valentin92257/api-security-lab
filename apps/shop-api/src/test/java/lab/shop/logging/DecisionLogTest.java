package lab.shop.logging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionLogTest {
    @TempDir
    Path directory;

    @Test
    void writesCompleteJsonLinesConcurrentlyAndAppendsAfterReopening() throws Exception {
        Path file = directory.resolve("decisions.jsonl");
        var logger = (Logger) LoggerFactory.getLogger("lab.decision");
        var ids = IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID().toString()).toList();
        var appender = attach(logger, file);
        try {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = ids.stream().map(id -> executor.submit(() -> {
                    MDC.put("secret", "token-secret");
                    try {
                        DecisionLog.record(id, "shop.object-owner", DecisionLog.Kind.SECURITY,
                                DecisionLog.Decision.BLOCK, "OWNER_MISMATCH");
                    } finally {
                        MDC.clear();
                    }
                })).toList();
                for (var future : futures) {
                    future.get();
                }
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        appender = attach(logger, file);
        try {
            DecisionLog.record(ids.getFirst(), "http.request-id", DecisionLog.Kind.TECHNICAL,
                    DecisionLog.Decision.ALLOW, "VALID_REQUEST_ID");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        var lines = Files.readAllLines(file);
        assertThat(lines).hasSize(101);
        var json = new JsonMapper();
        var entries = lines.stream().map(json::readTree).toList();
        assertThat(entries.subList(0, 100)).allSatisfy(entry -> {
            assertThat(entry.get("event").asText()).isEqualTo("policy_decision");
            assertThat(entry.get("requestId").asText()).isIn(ids);
            assertThat(entry.get("trace_id").asText()).isEqualTo(entry.get("requestId").asText());
            assertThat(entry.get("policyId").asText()).isEqualTo("shop.object-owner");
            assertThat(entry.get("decision").asText()).isEqualTo("BLOCK");
            assertThat(entry.get("reason").asText()).isEqualTo("OWNER_MISMATCH");
            assertThat(entry.get("kind").asText()).isEqualTo("SECURITY");
            assertThat(entry.get("time").asText()).endsWith("Z");
        });
        assertThat(entries.subList(0, 100).stream().map(e -> e.get("requestId").asText()).toList())
                .containsExactlyInAnyOrderElementsOf(ids);
        assertThat(entries.getLast().get("decision").asText()).isEqualTo("ALLOW");
        assertThat(Files.readString(file)).doesNotContain("token-secret");
    }

    @Test
    void rejectsFreeTextReasonsAndInvalidIdentifiers() {
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> DecisionLog.record(id, "http.request-id", DecisionLog.Kind.TECHNICAL,
                DecisionLog.Decision.BLOCK, "password=secret\nforged")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecisionLog.record(id, "bad\npolicy", DecisionLog.Kind.TECHNICAL,
                DecisionLog.Decision.BLOCK, "REJECTED")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecisionLog.record("1-1-1-1-1", "http.request-id", DecisionLog.Kind.TECHNICAL,
                DecisionLog.Decision.ALLOW, "VALID_REQUEST_ID")).isInstanceOf(IllegalArgumentException.class);
    }

    private FileAppender<ILoggingEvent> attach(Logger logger, Path file) {
        var encoder = new PatternLayoutEncoder();
        encoder.setContext(logger.getLoggerContext());
        encoder.setPattern("%msg%n");
        encoder.start();
        var appender = new FileAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.setFile(file.toString());
        appender.setAppend(true);
        appender.setEncoder(encoder);
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
