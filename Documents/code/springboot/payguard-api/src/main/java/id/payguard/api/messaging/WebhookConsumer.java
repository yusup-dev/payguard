package id.payguard.api.messaging;

import com.rabbitmq.client.Channel;
import id.payguard.api.messaging.dto.PaymentCallbackEvent;
import id.payguard.api.repository.TransactionRepository;
import id.payguard.api.service.WebhookDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

// bertugas mengambil event dari RabbitMQ.
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookConsumer {

    private final WebhookDispatchService webhookDispatchService;
    private final PaymentEventPublisher eventPublisher;
    private final TransactionRepository transactionRepository;

    @Value("${app.rabbitmq.max-retry-attempts:3}")
    private int maxRetryAttempts;

    private static final long[] BACKOFF_DELAYS_SECONDS = {0, 5, 15, 45, 120};

    @RabbitListener(
            queues = "${app.rabbitmq.queue}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void onPaymentCallbackEvent(
            PaymentCallbackEvent event,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("[AMQP-CONSUMER] Received PaymentCallbackEvent. " +
                        "deliveryTag={}, transactionId={}, merchantId={}, status={}, attempt={}/{}, webhookUrl={}",
                deliveryTag, event.getTransactionId(), event.getMerchantId(),
                event.getStatus(), event.getRetryAttempt(), maxRetryAttempts, event.getWebhookUrl());

        try {
            if (event.getRetryAttempt() > maxRetryAttempts) {
                handlePermanentFailure(event, channel, deliveryTag);
                return;
            }

            // Apply exponential backoff delay (for retry attempts)
            applyBackoffDelay(event.getRetryAttempt());

            // Dispatch the HTTP webhook
            boolean delivered = webhookDispatchService.dispatch(event, event.getWebhookUrl());

            if (delivered) {
                handleSuccessfulDelivery(event, channel, deliveryTag);
            } else {
                handleRetry(event, channel, deliveryTag);
            }
        } catch (IOException e) {
            // IOException from channel.basicAck/Nack
            log.error("[AMQP-CONSUMER] IOException during ACK/NACK for transactionId={}, deliveryTag={}. " +
                            "Message will remain Unacked until consumer reconnects.",
                    event.getTransactionId(), deliveryTag, e);
            safeNack(channel, deliveryTag, false); // NACK without requeue → DLX
        } catch (Exception e) {
            // Catch-all: RuntimeException from dispatch, JSON deserialization, etc.
            log.error("[AMQP-CONSUMER] Unexpected exception for transactionId={}, deliveryTag={}. " +
                            "Sending to DLQ via NACK. error={}",
                    event.getTransactionId(), deliveryTag, e.getMessage(), e);
            safeNack(channel, deliveryTag, false); // NACK without requeue → DLX → DLQ
        }
    }


    // Private helpers

    private void handlePermanentFailure(PaymentCallbackEvent event,
                                       Channel channel, long deliveryTag) throws IOException {

        log.error("[AMQP-CONSUMER] PERMANENT WEBHOOK FAILURE. transactionId={}, merchantId={}, url={}. " +
                        "Exhausted {} retry attempts. Message will NOT be retried. " +
                        "Manual intervention required.",
                event.getTransactionId(), event.getMerchantId(),
                event.getWebhookUrl(), maxRetryAttempts);

        // Update DB to reflect the final failed state
        transactionRepository.findById(event.getTransactionId()).ifPresent(tx -> {
            tx.setWebhookDelivered(false);
            tx.setWebhookAttempts(tx.getWebhookAttempts() + 1);
            transactionRepository.save(tx);
        });

        // NACK with requeue=false → message goes to Dead Letter Exchange → DLQ
        log.warn("[AMQP-NACK→DLQ] Sending to DLQ. transactionId={}, deliveryTag={}",
                event.getTransactionId(), deliveryTag);
        channel.basicNack(deliveryTag, false, false);
    }

    private void applyBackoffDelay(int retryAttempt) {
        if (retryAttempt <= 0) return;

        long delaySeconds = retryAttempt < BACKOFF_DELAYS_SECONDS.length
                ? BACKOFF_DELAYS_SECONDS[retryAttempt]
                : BACKOFF_DELAYS_SECONDS[BACKOFF_DELAYS_SECONDS.length - 1];

        if (delaySeconds > 0) {
            log.debug("Applying backoff delay of {}s before attempt {}", delaySeconds, retryAttempt);
            try {
                Thread.sleep(delaySeconds * 1000L);
            } catch (InterruptedException e){
                Thread.currentThread().interrupt();
                log.warn("Backoff sleep interrupted for retry attempt {}", retryAttempt);
            }
        }
    }

    private void handleSuccessfulDelivery(PaymentCallbackEvent event, Channel channel, long deliveryTag) throws IOException {
        transactionRepository.findById(event.getTransactionId())
                .ifPresent(tx -> {
                    tx.setWebhookDelivered(true);
                    tx.setWebhookAttempts(tx.getWebhookAttempts() + 1);
                    transactionRepository.save(tx);
                });

        /**
         * ACK (acknowledgment) adalah pemberitahuan kepada RabbitMQ bahwa pesan sudah selesai diproses oleh consumer.
         */
        log.info("[AMQP-ACK] Sending ACK. transactionId={}, deliveryTag={}",
                event.getTransactionId(), deliveryTag);
        channel.basicAck(deliveryTag, false);
        log.info("[AMQP-ACK] Webhook delivered and message ACK'd. transactionId={}",
                event.getTransactionId());
    }

    private void handleRetry(PaymentCallbackEvent event, Channel channel, long deliveryTag) throws IOException {
        int nextAttempt = event.getRetryAttempt() + 1;

        if (nextAttempt > maxRetryAttempts) {
            handlePermanentFailure(event, channel, deliveryTag);
            return;
        }

        // Update webhook attempts in DB
        transactionRepository.findById(event.getTransactionId()).ifPresent(tx -> {
            tx.setWebhookAttempts(tx.getWebhookAttempts() + 1);
            transactionRepository.save(tx);
        });

        // Re-publish to main exchange with incremented retry counter.
        // If republish fails (throws RuntimeException), do NOT ACK the original message —
        // NACK it to DLQ instead to avoid losing the event.
        try {
            eventPublisher.republishForRetry(event);
        } catch (Exception republishEx) {
            log.error("[AMQP-RETRY] Failed to republish retry event for transactionId={}. " +
                            "Sending original message to DLQ. error={}",
                    event.getTransactionId(), republishEx.getMessage());
            safeNack(channel, deliveryTag, false);
            return;
        }

        // ACK the original message — the retry is now a new message in the queue
        log.info("[AMQP-ACK] ACKing original message after successful re-publish. " +
                        "transactionId={}, deliveryTag={}",
                event.getTransactionId(), deliveryTag);
        channel.basicAck(deliveryTag, false);

        long delaySeconds = nextAttempt < BACKOFF_DELAYS_SECONDS.length
                ? BACKOFF_DELAYS_SECONDS[nextAttempt]
                : BACKOFF_DELAYS_SECONDS[BACKOFF_DELAYS_SECONDS.length - 1];

        log.warn("[AMQP-RETRY] Webhook delivery failed. Retry scheduled. attempt={}/{}, transactionId={}, " +
                        "nextAttemptDelay=~{}s",
                nextAttempt, maxRetryAttempts, event.getTransactionId(), delaySeconds);
    }

    /** Melakukan NACK pada pesan dengan aman, serta mengabaikan (swallowing) IOException untuk mencegah tertutupnya kesalahan yang sebenarnya.**/
    private void safeNack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            log.warn("[AMQP-NACK] Sending NACK. deliveryTag={}, requeue={}", deliveryTag, requeue);
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException e) {
            log.error("[AMQP-NACK] Failed to NACK message with deliveryTag={}", deliveryTag, e);
        }
    }


}


