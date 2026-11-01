package id.payguard.api.exception;

import id.payguard.api.entity.enums.TransactionStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class IllegalStateTransitionException extends RuntimeException {
    private final TransactionStatus from;
    private final TransactionStatus to;

    public IllegalStateTransitionException(TransactionStatus from, TransactionStatus to) {
        super(String.format(
                "Illegal state transition: cannot move transaction from '%s' to '%s'. " +
                        "Only PENDING transactions can be updated.",
                from, to
        ));
        this.from = from;
        this.to = to;
    }

    public TransactionStatus getFrom() { return from; }
    public TransactionStatus getTo()   { return to; }
}
