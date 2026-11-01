package id.payguard.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException() {
        super("Request signature verification failed. Ensure you are using the correct secret key and signing the correct payload.");
    }

    public InvalidSignatureException(String message) {
        super(message);
    }
}