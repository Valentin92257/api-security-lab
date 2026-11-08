package lab.shop.api.exception;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message, Exception cause) {
        super(message, cause);
    }

    public NotFoundException(String message) {
        super(message);
    }
}
