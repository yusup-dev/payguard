package id.payguard.api.service;



import id.payguard.api.config.AppProperties;
import id.payguard.api.exception.InvalidSignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Service
public class SignatureService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";


    private final Map<String, String> merchantSecrets;

    public SignatureService(AppProperties appProperties) {
        this.merchantSecrets = appProperties.getSignature().getMerchantSecrets();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────
    public void verifySignature(String merchantId, String orderId, BigDecimal amount,
                                String providedSignature) {

        String secret = resolveSecret(merchantId);
        String payload = buildPayload(merchantId, orderId, amount);
        String expectedSignature = computeHmacSha256(payload, secret);

        // Constant-time comparison — prevents timing attacks
        boolean matches = MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                providedSignature.toLowerCase().getBytes(StandardCharsets.UTF_8)
        );

        if (!matches) {
            log.warn("Signature mismatch for merchantId={}, orderId={}. " +
                            "Expected={}, Provided={}",
                    merchantId, orderId, expectedSignature, providedSignature);
            throw new InvalidSignatureException();
        }

        log.debug("Signature verified successfully for merchantId={}, orderId={}", merchantId, orderId);
    }

    public String generateSignature(String merchantId, String orderId, BigDecimal amount) {
        String secret = resolveSecret(merchantId);
        String payload = buildPayload(merchantId, orderId, amount);
        return computeHmacSha256(payload, secret);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String buildPayload(String merchantId, String orderId, BigDecimal amount) {
        // Use toPlainString() to avoid scientific notation (e.g., 1.5E+5 → 150000)
        return merchantId + "|" + orderId + "|" + amount.toPlainString();
    }

    private String resolveSecret(String merchantId) {
        String secret = merchantSecrets.get(merchantId);
        if (secret == null || secret.isBlank()) {
            log.error("No signing secret configured for merchantId={}", merchantId);
            throw new InvalidSignatureException(
                    String.format("No signing secret configured for merchant '%s'. " +
                            "Ensure the merchant is registered.", merchantId)
            );
        }
        return secret;
    }

    private String computeHmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM
            );
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac); // Java 17+ HexFormat
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }
}
