package lab.contracts.json;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Converts and validates shared contract DTOs at Spring's message conversion stage.
 * Other Java types and non-JSON media types are left to the standard converters.
 */
public final class ContractJsonHttpMessageConverter extends AbstractHttpMessageConverter<Object> {

    private final ContractJsonCodec codec;

    public ContractJsonHttpMessageConverter(ContractJsonCodec codec) {
        super(StandardCharsets.UTF_8, MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
    }

    @Override
    protected boolean supports(Class<?> type) {
        return ContractValidator.supports(type);
    }

    @Override
    protected Object readInternal(Class<?> type, HttpInputMessage message) throws IOException {
        try {
            return codec.read(message.getBody(), type);
        } catch (ContractValidationException e) {
            throw new HttpMessageNotReadableException("Invalid JSON contract", e, message);
        }
    }

    @Override
    protected void writeInternal(Object body, HttpOutputMessage message) throws IOException {
        byte[] bytes;
        try {
            bytes = codec.writeAsBytes(body);
        } catch (ContractValidationException e) {
            throw new HttpMessageNotWritableException("Invalid JSON contract response", e);
        }
        message.getBody().write(bytes);
    }
}
