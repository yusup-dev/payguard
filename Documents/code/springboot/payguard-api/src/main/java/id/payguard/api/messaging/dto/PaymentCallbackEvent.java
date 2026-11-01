package id.payguard.api.messaging.dto;

import id.payguard.api.entity.enums.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCallbackEvent implements Serializable {

    /** The internal transaction UUID. */
    private UUID transactionId;

    /** Merchant identifier. */
    private String merchantId;

    /** The merchant-supplied order identifier. */
    private String orderId;

    /** Final settled amount. */
    private BigDecimal amount;

    /** Currency code (e.g., IDR). */
    private String currency;

    /** The new status after the state transition. */
    private TransactionStatus status;

    /** Virtual Account / Payment Reference Number. */
    private String vaNumber;

    /** Customer email. */
    private String customerEmail;

    /** Timestamp when the status change occurred. */
    private Instant statusChangedAt;

    /** The merchant webhook URL to notify. */
    private String webhookUrl;

    /**
     * Retry attempt counter — incremented by the consumer before re-publishing
     * to the DLQ. Used to implement exponential backoff.
     */
    @Builder.Default
    private int retryAttempt = 0;
}
