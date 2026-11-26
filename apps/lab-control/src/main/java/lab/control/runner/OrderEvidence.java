package lab.control.runner;

import tools.jackson.databind.JsonNode;

public record OrderEvidence(long id, long userId, long cartId, long cartRevision,
                            long subtotal, long discount, long total, String currency, int itemCount) {
    public static OrderEvidence from(JsonNode body) {
        if (body == null) {
            return null;
        }
        return new OrderEvidence(body.get("id").asLong(), body.get("userId").asLong(),
                body.get("cartId").asLong(), body.get("cartRevision").asLong(),
                body.get("subtotal").asLong(), body.get("discount").asLong(), body.get("total").asLong(),
                body.get("currency").asString(), body.get("items").size());
    }
}
