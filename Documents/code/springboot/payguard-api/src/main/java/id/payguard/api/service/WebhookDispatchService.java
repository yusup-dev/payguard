package id.payguard.api.service;

import id.payguard.api.messaging.dto.PaymentCallbackEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

// bertugas mengirim HTTP POST ke server merchant.

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDispatchService {

    private final RestTemplate webhookRestTemplate;

    public boolean dispatch(PaymentCallbackEvent event, String webhookUrl){
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("No webhook URL configured for merchantId={}. Skipping dispatch.", event.getMerchantId());
            return true;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        headers.set("X-PayGuard-Event", "payment.status_changed");
        headers.set("X-PayGuard-Transaction-Id", event.getTransactionId().toString());

        HttpEntity<PaymentCallbackEvent> httpEntity = new HttpEntity<>(event, headers);

        try{
            log.info("Dispatching webhook to url={} for transactionId={}, status={}",
                    webhookUrl, event.getTransactionId(), event.getStatus());

            ResponseEntity<String> response = webhookRestTemplate.exchange(
                    webhookUrl,
                    HttpMethod.POST,
                    httpEntity,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Webhook delivered successfully. transactionId={}, merchantUrl={}, httpStatus={}",
                        event.getTransactionId(), webhookUrl, response.getStatusCode());
                return true;
            } else {
                log.warn("Webhook endpoint returned non-2xx response. transactionId={}, url={}, status={}",
                        event.getTransactionId(), webhookUrl, response.getStatusCode());
                return false;
            }
        } catch (HttpClientErrorException | HttpServerErrorException e){
            log.error("HTTP error from merchant webhook. transactionId={}, url={}, status={}, body={}",
                    event.getTransactionId(), webhookUrl, e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (ResourceAccessException e) {
            log.error("Connection failure dispatching webhook. transactionId={}, url={}, error={}",
                    event.getTransactionId(), webhookUrl, e.getMessage());
            return false;
        } catch (Exception e){
            log.error("Unexpected error dispatching webhook for transactionId={}", event.getTransactionId(), e);
            return false;
        }
    }
}
