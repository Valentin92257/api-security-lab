package lab.contracts.json;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * Configures strict Jackson {@link JsonMapper} settings matching the repository's JSON Schema rules
 * ({@code additionalProperties: false}, no duplicate keys, no implicit scalar type coercion).
 */
public final class ContractJsonSupport {

    private ContractJsonSupport() {
    }

    public static JsonMapper.Builder configure(JsonMapper.Builder builder) {
        builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);

        builder.withCoercionConfig(LogicalType.Textual, cfg -> {
            cfg.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
            cfg.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            cfg.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            cfg.setCoercion(CoercionInputShape.Array, CoercionAction.Fail);
            cfg.setCoercion(CoercionInputShape.Object, CoercionAction.Fail);
        });

        builder.withCoercionConfig(LogicalType.Integer, cfg -> {
            cfg.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            cfg.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            cfg.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            cfg.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        });

        builder.withCoercionConfig(LogicalType.Float, cfg -> {
            cfg.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            cfg.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            cfg.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        });

        builder.withCoercionConfig(LogicalType.Boolean, cfg -> {
            cfg.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            cfg.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
            cfg.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            cfg.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        });

        return builder;
    }

    public static JsonMapper createJsonMapper() {
        return configure(JsonMapper.builder()).build();
    }
}
