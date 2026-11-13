package lab.contracts.json;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/** Common JSON parsing rules. Model-specific constraints are checked separately. */
public final class ContractJsonSupport {

    private ContractJsonSupport() {
    }

    public static JsonMapper.Builder configure(JsonMapper.Builder builder) {
        // All JsonReadFeature flags enable non-standard JSON extensions.
        builder.disable(JsonReadFeature.values());
        builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .disable(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS);

        rejectCoercions(builder, LogicalType.Textual,
                CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean);
        rejectCoercions(builder, LogicalType.Integer,
                CoercionInputShape.String, CoercionInputShape.EmptyString,
                CoercionInputShape.Float, CoercionInputShape.Boolean);
        rejectCoercions(builder, LogicalType.Float,
                CoercionInputShape.String, CoercionInputShape.EmptyString, CoercionInputShape.Boolean);
        rejectCoercions(builder, LogicalType.Boolean,
                CoercionInputShape.String, CoercionInputShape.EmptyString,
                CoercionInputShape.Integer, CoercionInputShape.Float);
        return builder;
    }

    public static JsonMapper createJsonMapper() {
        return configure(JsonMapper.builder()).build();
    }

    private static void rejectCoercions(JsonMapper.Builder builder, LogicalType target,
            CoercionInputShape... inputShapes) {
        builder.withCoercionConfig(target, config -> {
            for (CoercionInputShape shape : inputShapes) {
                config.setCoercion(shape, CoercionAction.Fail);
            }
        });
    }
}
