package id.payguard.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private final Idempotency idempotency = new Idempotency();
    private final Signature signature = new Signature();
    private final Webhook webhook = new Webhook();

    @Getter
    @Setter
    public static class Idempotency {
        private long ttlSeconds =86400;
    }

    @Getter
    @Setter
    public static class Signature {
        private Map<String, String> merchantSecrets = new HashMap<>();
    }

    @Getter
    @Setter
    public static class Webhook{
        private Map<String, String> merchantWebhookUrls = new HashMap<>();
        private long connectTimeoutMs = 3000;
        private long readTimeout = 5000;
    }
}
