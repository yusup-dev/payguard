package id.payguard.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(
        name = "PaymentRequest",
        description = "Payload for initiating a new payment transaction"
)
public class PaymentRequest {
    @NotBlank(message = "merchant_id is required")
    @Size(max = 64, message = "merchant_id must be at most 64 characters")
    @Schema(description = "Unique identifier for the merchant", example = "MERCH-001", requiredMode = Schema.RequiredMode.REQUIRED)
    private String merchantId;

    @NotBlank(message = "order_id is required")
    @Size(max = 128, message = "order_id must be at most 128 characters")
    @Schema(description = "Merchant-generated unique order identifier", example = "ORD-20241014-98765", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderId;

    @NotNull(message = "amount is required")
    @Positive(message = "amount must be a positive number")
    @Digits(integer = 17, fraction = 2, message = "amount has invalid precision")
    @Schema(description = "Payment amount (max 2 decimal places)", example = "150000.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO 4217 code")
    @Schema(description = "ISO 4217 currency code", example = "IDR", defaultValue = "IDR", requiredMode = Schema.RequiredMode.REQUIRED)
    private String currency;

    @NotBlank(message = "customer_email is required")
    @Email(message = "customer_email must be a valid email address")
    @Size(max = 255)
    @Schema(description = "Customer's email address", example = "budi.santoso@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    private String customerEmail;

    /**
     * HMAC-SHA256 hex signature.
     * Computed by the merchant as: {@code HMAC(merchantSecret, merchantId + "|" + orderId + "|" + amount)}
     */
    @NotBlank(message = "signature is required")
    @Schema(
            description = "HMAC-SHA256 hex signature. Computed as HMAC(secret, merchantId|orderId|amount)",
            example = "a3f9e2b1c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String signature;
}
