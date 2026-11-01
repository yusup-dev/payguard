package id.payguard.api.service;

import id.payguard.api.dto.request.MockCallbackRequest;
import id.payguard.api.dto.request.PaymentRequest;
import id.payguard.api.dto.response.PaymentResponse;
import id.payguard.api.entity.Transaction;
import id.payguard.api.entity.enums.TransactionStatus;
import id.payguard.api.exception.IllegalStateTransitionException;
import id.payguard.api.exception.TransactionNotFoundException;
import id.payguard.api.messaging.PaymentEventPublisher;
import id.payguard.api.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class PaymentService {

    private static final int VA_PREFIX = 88088;

    private static final long PAYMENT_EXPIRY_HOURS = 24;

    private final TransactionRepository transactionRepository;
    private final SignatureService signatureService;
    private final IdempotencyService idempotencyService;
    private final PaymentEventPublisher eventPublisher;
    private final Map<String, String> merchantWebhookUrls;
    private final ApplicationEventPublisher applicationEventPublisher;

    public PaymentService(TransactionRepository transactionRepository,
                          SignatureService signatureService,
                          IdempotencyService idempotencyService,
                          PaymentEventPublisher eventPublisher,
                          Map<String, String> merchantWebhookUrls,
                          ApplicationEventPublisher applicationEventPublisher) {
        this.transactionRepository = transactionRepository;
        this.signatureService = signatureService;
        this.idempotencyService = idempotencyService;
        this.eventPublisher = eventPublisher;
        this.merchantWebhookUrls = merchantWebhookUrls;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Transactional
    public PaymentResponse createPayment(PaymentRequest request, String idempotencyKey) {

        // Step 1: Signature verification
        signatureService.verifySignature(
                request.getMerchantId(),
                request.getOrderId(),
                request.getAmount(),
                request.getSignature()
        );
        log.info("Signature verified. merchantId={}, orderId={}", request.getMerchantId(), request.getOrderId());

        // Step 2: Idempotency check (Redis fast path)
        Optional<PaymentResponse> cachedResponse = idempotencyService.checkForExistingResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached response for idempotencyKey={}", idempotencyKey);
            return cachedResponse.get();
        }

        // Step 3: Acquire processing lock to prevent concurrent creation
        boolean lockAcquired = idempotencyService.acquireProcessingLock(idempotencyKey);
        if (!lockAcquired) {
            // Another instance is processing this key right now — wait and retry
            // In production, this would use a retry loop or 429 response
            log.warn("Could not acquire processing lock for idempotencyKey={}. " +
                    "Concurrent request in-flight.", idempotencyKey);
            throw new RuntimeException("Request is currently being processed. Please retry in a moment.");
        }

        try {
            // Step 4: Double-check after lock acquisition (in case the other instance just finished)
            cachedResponse = idempotencyService.checkForExistingResponse(idempotencyKey);
            if (cachedResponse.isPresent()) {
                return cachedResponse.get();
            }

            // Step 5: DB-level idempotency check (secondary guard; also catches requests where Redis TTL expired)
            Optional<Transaction> existingTx = transactionRepository.findByMerchantIdAndOrderId(
                    request.getMerchantId(), request.getOrderId()
            );
            if (existingTx.isPresent()) {
                log.warn("Duplicate order detected at DB level. merchantId={}, orderId={}. Returning existing transaction.",
                        request.getMerchantId(), request.getOrderId());
                return mapToResponse(existingTx.get());
            }

            // Step 6: Build and persist the transaction
            Transaction transaction = buildTransaction(request, idempotencyKey);
            Transaction saved;
            try {
                saved = transactionRepository.save(transaction);
                log.info("Transaction created. id={}, merchantId={}, orderId={}, vaNumber={}",
                        saved.getId(), saved.getMerchantId(), saved.getOrderId(), saved.getVaNumber());
            } catch (DataIntegrityViolationException e) {
                // Race condition: another node persisted the same order in the tiny window between our checks
                // Fall back to returning the existing record
                log.warn("DataIntegrityViolation during transaction creation — concurrent insert detected. " +
                                "Fetching existing record. merchantId={}, orderId={}",
                        request.getMerchantId(), request.getOrderId());
                Transaction existing = transactionRepository
                        .findByMerchantIdAndOrderId(request.getMerchantId(), request.getOrderId())
                        .orElseThrow(() -> new RuntimeException("Unexpected state: constraint violation but record not found."));
                return mapToResponse(existing);
            }

            // Step 7: Cache response in Redis
            PaymentResponse response = mapToResponse(saved);
            idempotencyService.storeResponse(idempotencyKey, response);

            return response;

        } finally {
            // Step 8: Always release the lock
            idempotencyService.releaseProcessingLock(idempotencyKey);
        }
    }

    @Transactional
    public void processCallback(MockCallbackRequest callbackRequest) {
        UUID transactionId = callbackRequest.getTransactionId();
        TransactionStatus newStatus = callbackRequest.getStatus();

        if (newStatus != TransactionStatus.SUCCESS && newStatus != TransactionStatus.FAILED) {
            throw new IllegalArgumentException(
                    "Invalid callback status. Only SUCCESS or FAILED are accepted from bank callbacks."
            );
        }

        Transaction transaction = transactionRepository.findByIdPessimisticLock(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        TransactionStatus currentStatus = transaction.getStatus();

        log.info("Processing callback for transactionId={}. currentStatus={}, newStatus={}",
                transactionId, currentStatus, newStatus);

        if (!TransactionStatus.isValidTransaction(currentStatus, newStatus)) {
            log.warn("Illegal state transition attempt. transactionId={}, from={}, to={}",
                    transactionId, currentStatus, newStatus);
            throw new IllegalStateTransitionException(currentStatus, newStatus);
        }

        // Apply state transition
        transaction.setStatus(newStatus);
        Transaction updated = transactionRepository.save(transaction);
        log.info("Transaction status updated. id={}, newStatus={}", updated.getId(), updated.getStatus());

        // IMPORTANT: Publish a Spring ApplicationEvent here — NOT directly to RabbitMQ.
        // The @TransactionalEventListener below will forward this to RabbitMQ ONLY AFTER
        // the database transaction has successfully committed.
        // This prevents publishing to RabbitMQ when the DB transaction rolls back.
        String webhookUrl = merchantWebhookUrls.get(updated.getMerchantId());
        applicationEventPublisher.publishEvent(
                new PaymentCallbackApplicationEvent(this, updated, webhookUrl)
        );
        log.info("PaymentCallbackApplicationEvent published to Spring context. " +
                        "transactionId={}, status={}. RabbitMQ publish will occur after DB commit.",
                updated.getId(), updated.getStatus());
    }

    /**
     * Listens for {@link PaymentCallbackApplicationEvent} and forwards it to RabbitMQ
     * <b>only after the enclosing database transaction has committed</b>.
     *
     * <p>Using {@code AFTER_COMMIT} prevents the scenario where a message is published
     * to RabbitMQ but the database transaction is later rolled back, leaving the consumer
     * to process an event for a transaction that no longer exists in the DB.
     *
     * <p>If the transaction rolls back, this listener is NOT called — no message is published.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentCallbackApplicationEvent(PaymentCallbackApplicationEvent applicationEvent) {
        log.info("[TX-EVENT] DB transaction committed. Forwarding to RabbitMQ. " +
                        "transactionId={}, status={}",
                applicationEvent.getTransaction().getId(),
                applicationEvent.getTransaction().getStatus());
        eventPublisher.publishPaymentCallback(
                applicationEvent.getTransaction(),
                applicationEvent.getWebhookUrl()
        );
    }

    /**
     * Internal Spring ApplicationEvent carrying the committed transaction data.
     * Used to bridge the @Transactional boundary before publishing to RabbitMQ.
     */
    public static class PaymentCallbackApplicationEvent extends org.springframework.context.ApplicationEvent {
        private final Transaction transaction;
        private final String webhookUrl;

        public PaymentCallbackApplicationEvent(Object source, Transaction transaction, String webhookUrl) {
            super(source);
            this.transaction = transaction;
            this.webhookUrl = webhookUrl;
        }

        public Transaction getTransaction() { return transaction; }
        public String getWebhookUrl() { return webhookUrl; }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getTransaction(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        return mapToResponse(transaction);
    }

    // Private Helper
    private Transaction buildTransaction(PaymentRequest request, String idempotencyKey) {
        Instant now = Instant.now();
        return Transaction.builder()
                .merchantId(request.getMerchantId())
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .customerEmail(request.getCustomerEmail())
                .vaNumber(generateVaNumber(request))
                .idempotencyKey(idempotencyKey)
                .status(TransactionStatus.PENDING)
                .webhookUrl(merchantWebhookUrls.get(request.getMerchantId()))
                .expiredAt(now.plus(PAYMENT_EXPIRY_HOURS, ChronoUnit.HOURS))
                .build();
    }

    /**
     * Generates a Virtual Account number.
     *
     * <p>In production, this would call a bank's VA allocation API.
     * Here we simulate: {@code <VA_PREFIX> + <last 8 digits of amount> + <4 random digits>}
     */
    private String generateVaNumber(PaymentRequest request) {
        String amountSuffix = String.valueOf(request.getAmount().longValue());
        // Take last 8 digits of amount, zero-pad if shorter
        amountSuffix = String.format("%08d", Long.parseLong(amountSuffix) % 100_000_000L);
        int random = ThreadLocalRandom.current().nextInt(1000, 9999);
        return VA_PREFIX + amountSuffix + random;
    }

    private PaymentResponse mapToResponse(Transaction tx) {
        return PaymentResponse.builder()
                .transactionId(tx.getId())
                .merchantId(tx.getMerchantId())
                .orderId(tx.getOrderId())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .vaNumber(tx.getVaNumber())
                .status(tx.getStatus())
                .createdAt(tx.getCreatedAt())
                .expiredAt(tx.getExpiredAt())
                .build();
    }



}
