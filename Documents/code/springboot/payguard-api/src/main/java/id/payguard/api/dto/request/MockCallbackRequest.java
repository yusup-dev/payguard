package id.payguard.api.dto.request;

import id.payguard.api.entity.enums.TransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(
        name = "MockCallbackRequest",
        description = "Payload to simulate a bank payment callback (SUCCESS or FAILED)"
)
public class MockCallbackRequest {
    @NotNull(message = "transaction_id is required")
    @Schema(
            description = "The UUID of the transaction to update",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private UUID transactionId;

    @NotNull(message = "status is required. Allowed values: SUCCESS, FAILED")
    @Schema(
            description = "The new payment status from the bank. Only SUCCESS or FAILED are accepted.",
            allowableValues = {"SUCCESS", "FAILED"},
            example = "SUCCESS",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private TransactionStatus status;

    @Schema(
            description = "Optional message or reason code from the bank",
            example = "Payment confirmed by BNI Virtual Account"
    )
    private String bankMessage;
}
