package id.payguard.api.entity;

import id.payguard.api.entity.enums.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "transactions",
        indexes = {
                @Index(name = "uidx_transactions_merchant_order", columnList = "merchant_id, order_id", unique = true),
                @Index(name = "idx_transactions_status", columnList = "status"),
                @Index(name = "idx_transactions_merchant", columnList = "merchant_id"),
                @Index(name = "idx_transactions_idempotency_key", columnList = "idempotency_key")

        }
)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "order_id", nullable = false, length = 128)
    private String orderId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "IDR";

    @Column(name = "customer_email", nullable = false, length = 255)
    private String customerEmail;

    @Column(name = "va_number", nullable = false, unique = true, length = 32)
    private String vaNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "webhook_url", length = 1024)
    private String webhookUrl;

    @Column(name = "webhook_delivered", nullable = false)
    @Builder.Default
    private Boolean webhookDelivered = Boolean.FALSE;

    @Column(name = "webhook_attempts", nullable = false)
    @Builder.Default
    private Integer webhookAttempts = 0;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

}
