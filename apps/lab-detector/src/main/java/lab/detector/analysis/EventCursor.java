package lab.detector.analysis;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import lab.contracts.detector.model.EventItem;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** A cursor is a position, not a snapshot or an authorization credential. */
public record EventCursor(int version, String filterHash, Instant time, UUID runId, String producer, int sequence) {
    public static EventCursor decode(String token, EventFilter filter, JsonMapper json) {
        if (token == null) {
            return null;
        }
        try {
            if (token.length() > 1024 || !token.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            var cursor = json.readValue(Base64.getUrlDecoder().decode(token), EventCursor.class);
            if (cursor == null || cursor.version != 1) {
                throw new IllegalArgumentException();
            }
            if (cursor.time == null || cursor.runId == null) {
                throw new IllegalArgumentException();
            }
            if (!"GATEWAY".equals(cursor.producer) && !"SHOP".equals(cursor.producer)) {
                throw new IllegalArgumentException();
            }
            if (cursor.sequence < 1 || cursor.sequence > 100000) {
                throw new IllegalArgumentException();
            }
            if (!hash(filter, json).equals(cursor.filterHash)) {
                throw new IllegalArgumentException();
            }
            // AccessLog.time and JDBC use OffsetDateTime, whose range is narrower than Instant.
            cursor.time.atOffset(ZoneOffset.UTC);
            return cursor;
        } catch (JacksonException | IllegalArgumentException | DateTimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный курсор или изменены фильтры");
        }
    }

    public static String encode(EventItem item, EventFilter filter, JsonMapper json) {
        var cursor = new EventCursor(1, hash(filter, json), item.getLog().getTime().toInstant(),
                item.getRunId(), item.getProducer().name(), item.getLog().getSequence());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(cursor));
    }

    private static String hash(EventFilter filter, JsonMapper json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(filter)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
