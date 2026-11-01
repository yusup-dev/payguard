package id.payguard.api.controller;

import id.payguard.api.dto.request.PaymentRequest;
import id.payguard.api.dto.response.ApiResponse;
import id.payguard.api.dto.response.PaymentResponse;
import id.payguard.api.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment creation and inquiry endpoints")
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(
            summary = "Create a new payment transaction",
            description = """
            Initiates a new payment and returns a Virtual Account number for the customer to complete the payment.
            
            **Idempotency:** Include `X-Idempotency-Key` header. Duplicate requests with the same key 
            return the original response without creating a new transaction.
            
            **Signature:** Compute `HMAC-SHA256(secretKey, merchantId|orderId|amount)` and include in `signature` field.
            """,
            parameters = {
                    @Parameter(
                            name = "X-Idempotency-Key",
                            description = "UUID or unique string to prevent duplicate transaction creation. " +
                                    "Required. Same key returns the same response for 24 hours.",
                            required = true,
                            example = "idem-key-20241014-001",
                            schema = @Schema(type = "string")
                    )
            }
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Transaction created successfully",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class),
                            examples = @ExampleObject(value = """
                    {
                      "success": true,
                      "code": "PAYMENT_CREATED",
                      "message": "Payment transaction created successfully",
                      "data": {
                        "transactionId": "550e8400-e29b-41d4-a716-446655440000",
                        "merchantId": "MERCH-001",
                        "orderId": "ORD-20241014-98765",
                        "amount": 150000.00,
                        "currency": "IDR",
                        "vaNumber": "880815000098765",
                        "status": "PENDING",
                        "createdAt": "2024-10-14T01:00:00Z",
                        "expiredAt": "2024-10-15T01:00:00Z"
                      }
                    }
                    """))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Duplicate request — returning cached response (idempotency replay)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Invalid HMAC signature"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation error in request body"
            )
    })
    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> createPayment(
            @RequestHeader(value = "X-Idempotency-Key")
            @NotBlank(message = "X-Idempotency-Key header is required")
            String idempotencyKey,
            @Valid @RequestBody PaymentRequest request
    ) {
        log.info("POST /api/v1/payments — merchantId={}, orderId={}, idempotencyKey={}",
                request.getMerchantId(), request.getOrderId(), idempotencyKey);

        PaymentResponse response = paymentService.createPayment(request, idempotencyKey);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("PAYMENT_CREATED", "Payment transaction created", response));
    }

    @Operation(
            summary = "Get transaction details",
            description = "Retrieves the current state and details of a payment transaction by its UUID."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Transaction found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Transaction not found")
    })
    @GetMapping("/{transactionId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getTransaction(
            @PathVariable UUID transactionId) {

        log.info("GET /api/v1/payments/{}", transactionId);
        PaymentResponse response = paymentService.getTransaction(transactionId);
        return ResponseEntity.ok(
                ApiResponse.success("TRANSACTION_FOUND", "Transaction retrieved successfully", response)
        );
    }

}
