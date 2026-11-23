package lab.contracts.json;

import lab.contracts.detector.model.AccessLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** JSONL framing for access logs; JSON parsing and validation are delegated to the shared codec. */
public final class AccessLogJsonlCodec {

    private final ContractJsonCodec codec;

    public AccessLogJsonlCodec(ContractJsonCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
    }

    /** Serializes one record without a line terminator, enforcing the 64 KiB per-record limit. */
    public String writeLine(AccessLog entry) {
        String line = codec.writeAsString(entry);
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new ContractValidationException(
                    "Serialized AccessLog JSONL line must not contain newline characters");
        }
        return line;
    }

    /** Parses and validates one JSONL record without a line terminator. */
    public AccessLog readLine(String jsonLine) {
        if (jsonLine == null || jsonLine.isBlank()) {
            throw new ContractValidationException("AccessLog JSONL line must not be blank");
        }
        if (jsonLine.indexOf('\n') >= 0 || jsonLine.indexOf('\r') >= 0) {
            throw new ContractValidationException("Single AccessLog JSONL line must not contain line breaks");
        }
        return codec.read(jsonLine, AccessLog.class);
    }

    /** Joins validated records with LF separators and no trailing line terminator. */
    public String writeLines(Collection<AccessLog> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        StringBuilder sb = new StringBuilder();
        for (AccessLog entry : entries) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(writeLine(entry));
        }
        return sb.toString();
    }

    /** Parses LF- or CRLF-separated records, ignoring blank lines. */
    public List<AccessLog> readLines(String jsonLines) {
        Objects.requireNonNull(jsonLines, "jsonLines must not be null");
        if (jsonLines.isBlank()) {
            return List.of();
        }
        String[] lines = jsonLines.split("\\r?\\n");
        List<AccessLog> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (!line.isBlank()) {
                result.add(readLine(line));
            }
        }
        return List.copyOf(result);
    }
}
