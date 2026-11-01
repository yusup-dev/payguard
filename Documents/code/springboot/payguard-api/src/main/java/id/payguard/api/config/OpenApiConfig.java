package id.payguard.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentGatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Payguard Payment Gateway API")
                        .description("""
                    ## Overview
                    A production-grade mini payment gateway engine built with Java 21 & Spring Boot 3.
                    
                    ## Key Features
                    - **Idempotent Payments**: `X-Idempotency-Key` header prevents duplicate charges
                    - **HMAC-SHA256 Security**: All payment requests require a valid request signature
                    - **State Machine**: Strict `PENDING → SUCCESS | FAILED | EXPIRED` transitions
                    - **Async Webhooks**: RabbitMQ-driven merchant notification with Exponential Backoff / DLQ retry
                    
                    ## Signature Computation
                    ```
                    payload   = merchantId + "|" + orderId + "|" + amount
                    signature = lowercase_hex( HMAC-SHA256(merchantSecret, payload) )
                    ```
                    
                    ## Test Merchants
                    | Merchant ID | Secret Key |
                    |-------------|-----------|
                    | MERCH-001   | s3cr3tKey!NicePay2024 |
                    | MERCH-002   | anotherS3cr3tKey!2024 |
                    """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("NicePay Engineering")
                                .email("engineering@payguard.co.id")
                                .url("https://www.payguard.co.id"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://www.payguard.co.id/terms")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development"),
                        new Server().url("https://api-sandbox.payguard.co.id").description("Sandbox")
                ));
    }
}