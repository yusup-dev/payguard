package id.payguard.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ApiResponse", description = "Standard API response envelope")
public class ApiResponse<T> {

    @Schema(description = "Indicates whether the request was processed successfully", example = "true")
    private boolean success;

    @Schema(description = "Machine-readable result code", example = "PAYMENT_CREATED")
    private String code;

    @Schema(description = "Human-readable message describing the result", example = "Payment transaction created successfully")
    private String message;

    @Schema(description = "Response payload. Null for error responses.")
    private T data;

    @Builder.Default
    @Schema(description = "UTC timestamp of the response", example = "2024-10-14T08:00:00Z")
    private Instant timestamp = Instant.now();

    public static <T> ApiResponse<T> success(String code, String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .code(code)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .code(code)
                .message(message)
                .build();
    }
}
