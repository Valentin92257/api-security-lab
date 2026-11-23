package lab.contracts.json;

/** A byte limit violation, distinguishable from malformed JSON by HTTP adapters. */
public class ContractPayloadTooLargeException extends ContractValidationException {
    public ContractPayloadTooLargeException(String message) {
        super(message);
    }
}
