package id.payguard.api.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.payguard.api.dto.response.PaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Manages idempotency keys in Redis to ensure at-most-once processing
 * semantics for payment creation requests.
 *
 * <h3>How It Works</h3>
 * <ol>
 *   <li>On first request: key is absent → we proceed to create the transaction,
 *       then store the serialized response in Redis with a TTL.</li>
 *   <li>On duplicate request: key is present → we return the cached response
 *       directly without re-executing the business logic.</li>
 * </ol>
 *
 * <h3>Race Condition Handling</h3>
 * <p>We use {@code SET NX} (set if not exists) via {@link StringRedisTemplate#opsForValue()
 * #setIfAbsent} in a two-phase pattern:
 * <ol>
 *   <li>First, check if the key exists.</li>
 *   <li>After processing, store the result under the key.</li>
 * </ol>
 * <p>In a fully distributed environment with multiple gateway instances, a distributed
 * lock (Redisson {@code RLock}) would be used to guard the check-then-act window.
 * For this showcase, we accept a small window and rely on the DB unique constraint
 * ({@code uidx_transactions_merchant_order}) as the ultimate guard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:payment:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.idempotency.ttl-seconds:86400}")
    private long ttlSeconds;

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Checks Redis for the given idempotency key.
     *
     * @param idempotencyKey the key from {@code X-Idempotency-Key} header
     * @return the previously cached {@link PaymentResponse}, or empty if this is a new request
     */
    public Optional<PaymentResponse> checkForExistingResponse(String idempotencyKey) {
        String redisKey = buildRedisKey(idempotencyKey);
        String cachedValue = redisTemplate.opsForValue().get(redisKey);

        if (cachedValue == null) {
            log.debug("Idempotency miss for key={}", idempotencyKey);
            return Optional.empty();
        }

        log.info("Idempotency HIT for key={}. Returning cached response.", idempotencyKey);
        try {
            PaymentResponse response = objectMapper.readValue(cachedValue, PaymentResponse.class);
            return Optional.of(response);
        } catch (JsonProcessingException e) {
            // Cache corruption — treat as a miss and allow re-processing
            log.error("Failed to deserialize cached response for key={}. Treating as miss.", idempotencyKey, e);
            redisTemplate.delete(redisKey);
            return Optional.empty();
        }
    }

    /**
     * Stores the successful response in Redis with the configured TTL.
     * Call this ONLY after the transaction has been successfully persisted to the DB.
     *
     * @param idempotencyKey the key from {@code X-Idempotency-Key} header
     * @param response       the response to cache
     */
    public void storeResponse(String idempotencyKey, PaymentResponse response) {
        String redisKey = buildRedisKey(idempotencyKey);
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(redisKey, json, Duration.ofSeconds(ttlSeconds));
            log.debug("Idempotency response cached for key={}, ttl={}s", idempotencyKey, ttlSeconds);
        } catch (JsonProcessingException e) {
            // Non-fatal: the transaction is already committed. A subsequent duplicate
            // request will hit the DB fallback in PaymentService.
            log.error("Failed to serialize response for idempotency key={}. Transaction is committed.", idempotencyKey, e);
        }
    }

    /**
     * Marks an idempotency key as "in-flight" using SET NX before processing begins.
     * Returns {@code true} if the lock was acquired (this is the first request),
     * {@code false} if another instance is currently processing the same key.
     *
     * <p>Used to prevent concurrent duplicate requests from both passing the initial check.
     *
     * @param idempotencyKey the key from {@code X-Idempotency-Key} header
     * @return {@code true} if lock acquired successfully
     */
    public boolean acquireProcessingLock(String idempotencyKey) {
        String lockKey = "lock:" + buildRedisKey(idempotencyKey);
        // SET NX EX 30 — expires in 30s to avoid deadlock if the service crashes
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30));
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Releases the in-flight processing lock after the request completes.
     * In normal flow, the lock expires naturally via TTL, but explicit release
     * improves throughput.
     *
     * @param idempotencyKey the key from {@code X-Idempotency-Key} header
     */
    public void releaseProcessingLock(String idempotencyKey) {
        String lockKey = "lock:" + buildRedisKey(idempotencyKey);
        redisTemplate.delete(lockKey);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String buildRedisKey(String idempotencyKey) {
        return KEY_PREFIX + idempotencyKey;
    }
}
