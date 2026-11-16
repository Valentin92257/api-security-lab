package lab.contracts.json;

import lab.contracts.detector.model.AccessLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessLogJsonlCodecTest {

    private static final String ACCESS_LOG = """
            {"sequence":1,"requestId":"00000000-0000-4000-8000-000000000010",
             "time":"2026-10-23T12:00:00Z","remote_addr":"192.0.2.10","user":null,
             "method":"GET","path":"/orders","route":null,"status":200,"bytes":0,
             "request_time":0.01,"user_agent":null,"req_hash":null}
            """;
    private final ContractJsonCodec jsonCodec = new ContractJsonCodec();
    private final AccessLogJsonlCodec codec = new AccessLogJsonlCodec(jsonCodec);

    @Test
    void roundTripsRecordsAndAcceptsCrLfAndBlankSeparators() {
        var first = jsonCodec.read(ACCESS_LOG, AccessLog.class);
        var second = jsonCodec.read(ACCESS_LOG, AccessLog.class).sequence(2);
        var entries = List.of(first, second);

        String line = codec.writeLine(first);
        assertThat(line).doesNotContain("\n", "\r");
        assertThat(codec.readLine(line)).isEqualTo(first);

        String lines = codec.writeLines(entries);
        assertThat(lines.lines().count()).isEqualTo(2);
        assertThat(codec.readLines(lines)).isEqualTo(entries);
        assertThat(codec.readLines("\r\n" + lines.replace("\n", "\r\n\r\n") + "\r\n"))
                .isEqualTo(entries);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n"})
    void rejectsLineBreaksWithinSingleRecord(String separator) {
        String line = codec.writeLine(jsonCodec.read(ACCESS_LOG, AccessLog.class));
        assertThatThrownBy(() -> codec.readLine(line.replaceFirst("\\{", "{" + separator)))
                .isInstanceOf(ContractValidationException.class).hasMessageContaining("line breaks");
    }

    @Test
    void enforcesContractValidationForReadAndWrite() {
        assertThatThrownBy(() -> codec.readLine("{}"))
                .isInstanceOf(ContractValidationException.class).hasMessageContaining("sequence");
        assertThatThrownBy(() -> codec.writeLine(new AccessLog()))
                .isInstanceOf(ContractValidationException.class).hasMessageContaining("sequence");

        String line = codec.writeLine(jsonCodec.read(ACCESS_LOG, AccessLog.class));
        String oversized = line + " ".repeat(ContractValidator.MAX_ACCESS_LOG_BYTES + 1 - line.length());
        assertThatThrownBy(() -> codec.readLine(oversized))
                .isInstanceOf(ContractValidationException.class).hasMessageContaining("exceeds limit");
    }

    @Test
    void rejectsMultilineSerializationFromSuppliedMapper() {
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        var indentedCodec = new AccessLogJsonlCodec(new ContractJsonCodec(mapper, new ContractValidator()));
        var entry = jsonCodec.read(ACCESS_LOG, AccessLog.class);

        assertThatThrownBy(() -> indentedCodec.writeLine(entry))
                .isInstanceOf(ContractValidationException.class).hasMessageContaining("newline characters");
    }
}
