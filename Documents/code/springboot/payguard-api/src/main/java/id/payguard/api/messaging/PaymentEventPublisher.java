package id.payguard.api.messaging;

import id.payguard.api.entity.Transaction;
import id.payguard.api.messaging.dto.PaymentCallbackEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;


// mengirim event pembayaran ke RabbitMQ.
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {
    private final RabbitTemplate rabbitTemplate;

    /**
     * Exchange adalah komponen RabbitMQ yang menerima pesan dari publisher dan menentukan ke mana pesan tersebut dirutekan.
     */
    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    /**
     * Routing key adalah string yang digunakan exchange untuk menentukan antrean tujuan berdasarkan jenis exchange dan binding yang dikonfigurasi.
     */
    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    public void publishPaymentCallback(Transaction transaction, String webhookUrl) {
        PaymentCallbackEvent event = PaymentCallbackEvent.builder()
                .transactionId(transaction.getId())
                .merchantId(transaction.getMerchantId())
                .orderId(transaction.getOrderId())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .status(transaction.getStatus())
                .vaNumber(transaction.getVaNumber())
                .customerEmail(transaction.getCustomerEmail())
                .statusChangedAt(Instant.now())
                .webhookUrl(webhookUrl)
                .retryAttempt(0)
                .build();

        // Diagnostic log: show all routing details BEFORE publishing
        log.info("[AMQP-PUBLISH] Attempting to publish PaymentCallbackEvent. " +
                        "exchange={}, routingKey={}, transactionId={}, merchantId={}, orderId={}, status={}, webhookUrl={}",
                exchange, routingKey,
                event.getTransactionId(), event.getMerchantId(),
                event.getOrderId(), event.getStatus(), event.getWebhookUrl());

        // CorrelationData links this publish call to the ConfirmCallback in RabbitMQConfig
        CorrelationData correlationData = new CorrelationData(
                "txn-" + event.getTransactionId().toString()
        );

        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, event, correlationData);
            // NOTE: "completed" only means the message was handed to the AMQP channel.
            // Actual broker ACK is reported asynchronously via ConfirmCallback in RabbitMQConfig.
            log.info("[AMQP-PUBLISH] convertAndSend() completed. " +
                            "exchange={}, routingKey={}, transactionId={}, status={}, correlationId={}",
                    exchange, routingKey, event.getTransactionId(), event.getStatus(), correlationData.getId());
        } catch (Exception e) {
            log.error("[AMQP-PUBLISH] CRITICAL: Failed to publish PaymentCallbackEvent to RabbitMQ. " +
                            "transactionId={} requires manual webhook dispatch. " +
                            "exchange={}, routingKey={}, error={}",
                    transaction.getId(), exchange, routingKey, e.getMessage(), e);
        }
    }

    public void republishForRetry(PaymentCallbackEvent event) {
        PaymentCallbackEvent retryEvent = PaymentCallbackEvent.builder()
                .transactionId(event.getTransactionId())
                .merchantId(event.getMerchantId())
                .orderId(event.getOrderId())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .status(event.getStatus())
                .vaNumber(event.getVaNumber())
                .customerEmail(event.getCustomerEmail())
                .statusChangedAt(event.getStatusChangedAt())
                .webhookUrl(event.getWebhookUrl())
                .retryAttempt(event.getRetryAttempt() + 1)
                .build();

        CorrelationData correlationData = new CorrelationData(
                "txn-" + retryEvent.getTransactionId() + "-retry-" + retryEvent.getRetryAttempt()
        );

        log.info("[AMQP-RETRY] Re-publishing for retry. exchange={}, routingKey={}, transactionId={}, attempt={}/{}",
                exchange, routingKey, retryEvent.getTransactionId(),
                retryEvent.getRetryAttempt(), "max");

        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, retryEvent, correlationData);
            log.info("[AMQP-RETRY] Re-publish completed. transactionId={}, attempt={}, correlationId={}",
                    retryEvent.getTransactionId(), retryEvent.getRetryAttempt(), correlationData.getId());
        } catch (Exception e) {
            log.error("[AMQP-RETRY] CRITICAL: Failed to re-publish retry event. " +
                            "transactionId={}, attempt={}, error={}",
                    retryEvent.getTransactionId(), retryEvent.getRetryAttempt(), e.getMessage(), e);
            // Re-throw so the caller (WebhookConsumer.handleRetry) knows the publish failed
            // and can NACK the original message to DLQ instead of ACKing it
            throw new RuntimeException("Failed to republish retry event for transactionId=" +
                    retryEvent.getTransactionId(), e);
        }
    }
}
