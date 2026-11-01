package id.payguard.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ topology configuration for the payment gateway.
 *
 * <h3>Topology</h3>
 * <pre>
 * Producers ──► payment.exchange (TOPIC)
 *                     │
 *                     ├──[payment.callback]──► payment.callback.queue
 *                     │                           (DLX: payment.dlx, TTL: 30s)
 *                     │                              │ on NACK / TTL expiry
 *                     │                              ▼
 *                     │                       payment.dlx (DIRECT)
 *                     │                              │
 *                     │                       payment.dlq (Dead Letter Queue)
 *                     │
 *                     └──[payment.dead]──────► (via DLX binding)
 * </pre>
 *
 * <h3>Retry via DLQ with Per-Message TTL</h3>
 * <p>When a consumer NACKs a message (or the message TTL in the main queue expires),
 * RabbitMQ routes it to the Dead Letter Exchange ({@code payment.dlx}), which delivers
 * it to the {@code payment.dlq}. A separate DLQ consumer (or future implementation)
 * can re-enqueue messages with exponential backoff delays.
 *
 * <p>In this implementation, the consumer itself manages retry:
 * <ul>
 *   <li>Retry attempts are tracked in the {@code PaymentCallbackEvent.retryAttempt} field</li>
 *   <li>After max retries, the event is ACK'd (to remove from main queue) but logged as permanently failed</li>
 * </ul>
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    @Value("${app.rabbitmq.queue}")
    private String queue;

    @Value("${app.rabbitmq.dlx}")
    private String dlx;

    @Value("${app.rabbitmq.dlq}")
    private String dlq;

    @Value("${app.rabbitmq.dlq-routing-key}")
    private String dlqRoutingKey;

    @Value("${app.rabbitmq.message-ttl-ms:30000}")
    private long messageTtlMs;

    // ─────────────────────────────────────────────────────────────────────────
    //  Exchange Declarations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Main topic exchange. TOPIC type allows flexible routing key patterns
     * (e.g., {@code payment.#} would match all payment events).
     */
    @Bean
    public TopicExchange paymentExchange() {
        return ExchangeBuilder
                .topicExchange(exchange)
                .durable(true)
                .build();
    }

    /**
     * Dead Letter Exchange — receives messages that are rejected or expired
     * from the main queue. DIRECT type for simple, deterministic routing.
     */
    @Bean
    public DirectExchange paymentDeadLetterExchange() {
        return ExchangeBuilder
                .directExchange(dlx)
                .durable(true)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Queue Declarations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Main callback queue. Configured with:
     * <ul>
     *   <li>{@code x-dead-letter-exchange}: routes failed messages to the DLX</li>
     *   <li>{@code x-dead-letter-routing-key}: routing key used when publishing to DLX</li>
     *   <li>{@code x-message-ttl}: messages expire after this many ms if not consumed</li>
     * </ul>
     */
    @Bean
    public Queue paymentCallbackQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", dlx);
        args.put("x-dead-letter-routing-key", dlqRoutingKey);
        args.put("x-message-ttl", messageTtlMs);
        return QueueBuilder
                .durable(queue)
                .withArguments(args)
                .build();
    }

    /**
     * Dead Letter Queue — holds messages that couldn't be delivered.
     * Monitor this queue for failed webhook deliveries that need manual intervention.
     */
    @Bean
    public Queue paymentDeadLetterQueue() {
        return QueueBuilder
                .durable(dlq)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Binding Declarations
    // ─────────────────────────────────────────────────────────────────────────

    /** Binds the main callback queue to the payment exchange with the configured routing key. */
    @Bean
    public Binding paymentCallbackBinding(Queue paymentCallbackQueue, TopicExchange paymentExchange) {
        return BindingBuilder
                .bind(paymentCallbackQueue)
                .to(paymentExchange)
                .with(routingKey);
    }

    /** Binds the DLQ to the Dead Letter Exchange. */
    @Bean
    public Binding deadLetterBinding(Queue paymentDeadLetterQueue, DirectExchange paymentDeadLetterExchange) {
        return BindingBuilder
                .bind(paymentDeadLetterQueue)
                .to(paymentDeadLetterExchange)
                .with(dlqRoutingKey);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Message Converter
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Use Jackson JSON serialization for RabbitMQ messages instead of Java
     * serialization. This makes messages human-readable in the RabbitMQ
     * Management UI and avoids class-compatibility issues across deployments.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RabbitTemplate
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Configures the {@link RabbitTemplate} with JSON message conversion,
     * mandatory flag, publisher confirms, and publisher returns.
     *
     * <p><b>mandatory=true + ReturnsCallback</b>: If a message cannot be routed to any
     * queue (e.g., wrong routing key or missing binding), the broker returns it here
     * instead of silently dropping it.
     *
     * <p><b>ConfirmCallback</b>: Called when the broker acknowledges (or nacks) receipt
     * of the message at the exchange level. Requires publisher-confirms to be enabled
     * on the connection factory (see {@link #configureCachingConnectionFactory}).
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        // Enable publisher confirms + returns on the underlying connection factory
        configureCachingConnectionFactory(connectionFactory);

        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());

        // mandatory=true: broker will return unroutable messages instead of dropping them
        template.setMandatory(true);

        // ReturnsCallback: fired when a message is returned because it couldn't be routed
        template.setReturnsCallback(returned -> {
            log.error("[AMQP-RETURN] Message UNROUTABLE — not delivered to any queue! " +
                            "exchange={}, routingKey={}, replyCode={}, replyText={}, messageBody={}",
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode(),
                    returned.getReplyText(),
                    new String(returned.getMessage().getBody()));
        });

        // ConfirmCallback: fired when broker ACKs or NACKs receipt of the message at exchange level
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                String id = correlationData != null ? correlationData.getId() : "(no correlation id)";
                log.debug("[AMQP-CONFIRM] Broker ACK'd message. correlationId={}", id);
            } else {
                log.error("[AMQP-CONFIRM] Broker NACK'd message! correlationId={}, cause={}",
                        correlationData != null ? correlationData.getId() : "(no correlation id)",
                        cause);
            }
        });

        return template;
    }

    /**
     * Enables publisher confirms and publisher returns on the connection factory.
     * Spring Boot auto-configures a {@link CachingConnectionFactory}; we cast and enable
     * the necessary flags here. This is required for {@link RabbitTemplate#setConfirmCallback}
     * and {@link RabbitTemplate#setReturnsCallback} to fire.
     */
    private void configureCachingConnectionFactory(ConnectionFactory connectionFactory) {
        if (connectionFactory instanceof CachingConnectionFactory cf) {
            cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            cf.setPublisherReturns(true);
            log.info("[AMQP-CONFIG] Publisher confirms (CORRELATED) and returns enabled on CachingConnectionFactory.");
        } else {
            log.warn("[AMQP-CONFIG] ConnectionFactory is not a CachingConnectionFactory (type={}). " +
                    "Publisher confirms and returns may not be available.",
                    connectionFactory.getClass().getSimpleName());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Listener Container Factory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Custom listener container factory with:
     * <ul>
     *   <li>Manual ACK mode for reliable processing</li>
     *   <li>JSON message converter</li>
     *   <li>Prefetch count of 1 (process one message at a time per consumer thread)</li>
     * </ul>
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        return factory;
    }
}
