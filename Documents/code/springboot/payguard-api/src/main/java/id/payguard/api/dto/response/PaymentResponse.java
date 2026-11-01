package id.payguard.api.dto.response;

import id.payguard.api.entity.enums.TransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        name = "PaymentResponse",
        description = "Details of the newly created payment transaction"
)
public class PaymentResponse {

    @Schema(
            description = "Internal transaction UUID",
            example = "550e8400-e29b-41d4-a716-446655440000"
    )
    private UUID transactionId;

    @Schema(
            description = "Merchant identifier",
            example = "MERCH-001"
    )
    private String merchantId;

    @Schema(
            description = "Merchant-generated order identifier",
            example = "ORD-20241014-98765"
    )
    private String orderId;

    @Schema(
            description = "Payment amount",
            example = "150000.00"
    )
    private BigDecimal amount;

    @Schema(
            description = "ISO 4217 currency code",
            example = "IDR"
    )
    private String currency;

    @Schema(
            description = "Virtual Account Number or Payment Reference. Customer pays via this number.",
            example = "8808150000098765"
    )
    private String vaNumber;

    @Schema(
            description = "Current transaction status",
            example = "PENDING"
    )
    private TransactionStatus status;

    @Schema(
            description = "UTC timestamp when the transaction was created",
            example = "2024-10-14T08:00:00Z"
    )
    private Instant createdAt;

    @Schema(
            description = "UTC payment expiry timestamp",
            example = "2024-10-15T08:00:00Z"
    )
    private Instant expiredAt;
}