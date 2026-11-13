package lab.contracts.json;

import lab.contracts.control.model.CreateTrialRequest;
import lab.contracts.control.model.RunReport;
import lab.contracts.control.model.TrialPage;
import lab.contracts.detector.model.AccessLog;
import lab.contracts.detector.model.IncidentPage;
import lab.contracts.detector.model.LogBatch;
import lab.contracts.runtime.model.RuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractValidatorTest {

    private static final String ACCESS_LOG = """
            {"sequence":1,"requestId":"00000000-0000-4000-8000-000000000010",
             "time":"2026-10-23T12:00:00Z","remote_addr":"192.0.2.10","user":null,
             "method":"GET","path":"/orders","route":null,"status":200,"bytes":0,
             "request_time":0.01,"user_agent":null,"req_hash":null}
            """;
    private final ContractJsonCodec codec = new ContractJsonCodec();

    static Stream<Arguments> invalidPayloads() {
        return Stream.of(
                Arguments.of(CreateTrialRequest.class, "{}", "families"),
                Arguments.of(CreateTrialRequest.class, "{\"families\":[\"BOLA\"],\"seed\":null}", "seed"),
                Arguments.of(CreateTrialRequest.class, "{\"families\":[null]}", "families"),
                Arguments.of(CreateTrialRequest.class, "{\"families\":[\"BOLA\"],\"extra\":1}", "extra"),
                Arguments.of(IncidentPage.class, "{\"total\":0}", "items"),
                Arguments.of(TrialPage.class, "{\"total\":0}", "items"),
                Arguments.of(IncidentPage.class, "{\"items\":[null],\"total\":1}", "items"),
                Arguments.of(RunReport.class, """
                        {"runId":"00000000-0000-4000-8000-000000000020",
                         "mode":"VULNERABLE","state":"INCONCLUSIVE","incidents":null}
                        """, "cases"),
                Arguments.of(LogBatch.class, "{\"producer\":\"SHOP\",\"entries\":[null]}", "entries"),
                Arguments.of(RuntimeStatus.class, """
                        {"instanceId":"invalid","state":"OPEN","lastSequence":0}
                        """, "instanceId")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void rejectsSchemaViolationsInBothReadAndValidateJson(Class<?> type, String json, String property) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> codec.read(bytes, type))
                .isInstanceOf(ContractValidationException.class).hasMessageContaining(property);
        assertThatThrownBy(() -> codec.validateJson(bytes, type))
                .isInstanceOf(ContractValidationException.class).hasMessageContaining(property);
    }

    @Test
    void rejectsNullCollectionItemsWhenWritingObjects() {
        var entries = new ArrayList<AccessLog>();
        entries.add(null);
        var batch = new LogBatch(LogBatch.ProducerEnum.SHOP, entries);
        assertThatThrownBy(() -> codec.writeLogBatch(batch))
                .isInstanceOf(ContractValidationException.class).hasMessageContaining("entries");
        assertThatThrownBy(() -> codec.validator().validateObject(batch))
                .isInstanceOf(ContractValidationException.class).hasMessageContaining("entries");
    }

    @Test
    void preservesOptionalSeedAndRequiredNullableFieldsWhenWriting() {
        var request = codec.readCreateTrialRequest("{\"families\":[\"BOLA\"]}");
        assertThat(codec.writeCreateTrialRequest(request)).doesNotContain("seed");
        String json = codec.writeAccessLog(codec.readAccessLog(ACCESS_LOG));
        assertThat(json).contains("\"user\":null", "\"route\":null", "\"user_agent\":null", "\"req_hash\":null");
    }

    @Test
    void enforcesAccessLogByteBoundaryAndStopsReadingAfterLimit() throws Exception {
        byte[] atLimit = paddedJson(ACCESS_LOG, ContractValidator.MAX_ACCESS_LOG_BYTES);
        assertThat(codec.read(atLimit, AccessLog.class).getSequence()).isEqualTo(1);
        byte[] overLimit = paddedJson(ACCESS_LOG, ContractValidator.MAX_ACCESS_LOG_BYTES + 10);
        var input = new ByteArrayInputStream(overLimit);
        assertThatThrownBy(() -> codec.read(input, AccessLog.class))
                .isInstanceOf(ContractValidationException.class).hasMessageContaining("exceeds limit");
        assertThat(input.available()).isEqualTo(9);
    }

    @Test
    void enforcesBatchByteBoundary() {
        String batch = "{\"producer\":\"SHOP\",\"entries\":[" + ACCESS_LOG + "]}";
        assertThat(codec.readLogBatch(paddedJson(batch, ContractValidator.MAX_LOG_BATCH_BYTES))
                .getEntries()).hasSize(1);
        assertThatThrownBy(() -> codec.readLogBatch(
                paddedJson(batch, ContractValidator.MAX_LOG_BATCH_BYTES + 1)))
                .isInstanceOf(ContractValidationException.class).hasMessageContaining("exceeds limit");
    }

    @Test
    void rejectsNonFiniteNumbersAndTrailingJson() {
        AccessLog log = codec.readAccessLog(ACCESS_LOG);
        log.setRequestTime(Double.POSITIVE_INFINITY);
        assertThatThrownBy(() -> codec.writeAccessLog(log)).isInstanceOf(ContractValidationException.class);
        assertThatThrownBy(() -> codec.readAccessLog(ACCESS_LOG + "{}"))
                .isInstanceOf(ContractValidationException.class);
    }

    private static byte[] paddedJson(String json, int size) {
        return (json + " ".repeat(size - json.getBytes(StandardCharsets.UTF_8).length))
                .getBytes(StandardCharsets.UTF_8);
    }
}
