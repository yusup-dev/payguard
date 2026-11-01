package id.payguard.api.entity.enums;

public enum TransactionStatus {
    PENDING, SUCCESS, FAILED, EXPIRED;

    public static boolean isValidTransaction(TransactionStatus current, TransactionStatus next){
        return switch (current) {
            case PENDING -> next == SUCCESS || next == FAILED || next == EXPIRED;
            case SUCCESS, FAILED, EXPIRED -> false;
        };
    }
}
