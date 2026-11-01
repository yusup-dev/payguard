package id.payguard.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateRequestException extends RuntimeException {

    private final String cachedResponseJson;

    public DuplicateRequestException(String idempotencyKey) {
        super(String.format("Request with idempotency key '%s' has already been processed.", idempotencyKey));
        this.cachedResponseJson = null;
    }

    public DuplicateRequestException(String idempotencyKey, String cachedResponseJson) {
        super(String.format("Request with idempotency key '%s' has already been processed.", idempotencyKey));
        this.cachedResponseJson = cachedResponseJson;
    }

    public String getCachedResponseJson() {
        return cachedResponseJson;
    }
}
