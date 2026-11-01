package id.payguard.api.controller;

import id.payguard.api.dto.request.MockCallbackRequest;
import id.payguard.api.dto.response.ApiResponse;
import id.payguard.api.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/internal")
@RequiredArgsConstructor
@Tag(name = "Internal / Simulation", description = "Internal endpoints for testing and simulation (not exposed in production)")
public class CallbackController {

    private final PaymentService paymentService;

    // ─────────────────────────────────────────────────────────────────────────
    //  POST /api/v1/internal/mock-payment-callback
    // ─────────────────────────────────────────────────────────────────────────

    @Operation(
            summary = "Simulate a bank payment callback",
            description = """
            Simulates the bank's asynchronous payment notification.
            
            - Transitions the transaction status from `PENDING` to `SUCCESS` or `FAILED`.
            - Illegal transitions (e.g., `SUCCESS` → `FAILED`) are rejected with HTTP 409.
            - Upon successful transition, publishes a `PaymentCallbackEvent` to RabbitMQ,
              which triggers the merchant webhook dispatch.
            
            **⚠ Internal Use Only:** In production, this endpoint would be replaced by the
            real bank-to-gateway notification channel.
            """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Callback processed. Transaction state updated and webhook dispatch triggered.",
                    content = @Content(examples = @ExampleObject(value = """
                {
                  "success": true,
                  "code": "CALLBACK_PROCESSED",
                  "message": "Transaction status updated to SUCCESS. Webhook dispatch initiated.",
                  "timestamp": "2024-10-14T01:05:00Z"
                }
                """))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Illegal state transition (e.g., attempting to set SUCCESS → FAILED)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Transaction not found"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Invalid callback status (only SUCCESS or FAILED are accepted)"
            )
    })
    @PostMapping("/mock-payment-callback")
    public ResponseEntity<ApiResponse<Void>> mockPaymentCallback(
            @Valid @RequestBody MockCallbackRequest callbackRequest
            ) {
        log.info("POST /api/v1/internal/mock-payment-callback — transactionId={}, status={}",
                callbackRequest.getTransactionId(), callbackRequest.getStatus());

        paymentService.processCallback(callbackRequest);

        String message = String.format(
                "Transaction status updated to %s. Webhook dispatch initiated via RabbitMQ.",
                callbackRequest.getStatus()
        );

        return ResponseEntity.ok(
                ApiResponse.success("CALLBACK_PROCESSED", message, null)
        );
    }
}
