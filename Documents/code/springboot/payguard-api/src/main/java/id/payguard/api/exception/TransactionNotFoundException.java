package id.payguard.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(UUID id) {
        super(String.format("Transaction with id '%s' was not found.", id));
    }

    public TransactionNotFoundException(String field, String value) {
        super(String.format("Transaction with %s='%s' was not found.", field, value));
    }
}

