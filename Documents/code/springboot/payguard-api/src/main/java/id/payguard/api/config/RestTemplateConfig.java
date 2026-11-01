package id.payguard.api.config;

import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class RestTemplateConfig {

    private final AppProperties appProperties;

    @Value("${app.webhook.connect-timeout-ms:3000}")
    private long connectTimeoutMs;

    @Value("${app.webhook.read-timeout-ms:5000}")
    private long readTimeoutMs;

    @Bean(name = "webhookRestTemplate")
    public RestTemplate webhookRestTemplate() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();

        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        return new RestTemplate(factory);
    }

    @Bean
    public Map<String, String> merchantWebhookUrls() {
        return appProperties.getWebhook().getMerchantWebhookUrls();
    }
}
