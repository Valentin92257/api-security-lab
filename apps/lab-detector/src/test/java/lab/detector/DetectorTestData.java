package lab.detector;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import lab.contracts.detector.model.Incident;
import lab.contracts.json.ContractJsonSupport;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class DetectorTestData {
    private static final JsonMapper JSON = ContractJsonSupport.createJsonMapper();

    static Incident incident(UUID id, UUID request, String time) {
        return new Incident(id, OffsetDateTime.parse(time), Set.of(request), "Test engine result");
    }

    static String path(UUID id) {
        return "/runs/" + id;
    }

    static String finishBody(int gateway, int shop) {
        return finishBody(gateway, shop, Instant.now().plusSeconds(30).toString());
    }

    static String finishBody(int gateway, int shop, String deadline) {
        return "{\"gatewayLastSequence\":" + gateway + ",\"shopLastSequence\":" + shop + ",\"deadlineAt\":\"" + deadline + "\"}";
    }

    static ObjectNode log(int sequence, UUID request) {
        return (ObjectNode) JSON.readTree("""
                {"sequence":%d,"requestId":"%s","time":"2026-11-16T10:00:00Z","remote_addr":"192.0.2.1",
                 "user":null,"method":"GET","path":"/api/v1/products?limit=2","route":null,"status":200,
                 "bytes":10,"request_time":0.1,"user_agent":null,"req_hash":"%s"}
                """.formatted(sequence, request, "a".repeat(64)));
    }

    static String batch(String producer, ObjectNode... entries) {
        var array = JSON.createArrayNode();
        for (ObjectNode entry : entries) {
            array.add(entry);
        }
        return JSON.createObjectNode().put("producer", producer).set("entries", array).toString();
    }

    private DetectorTestData() {
    }
}
