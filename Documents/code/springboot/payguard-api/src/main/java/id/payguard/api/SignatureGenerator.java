package id.payguard.api;

import id.payguard.api.service.SignatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class SignatureGenerator implements CommandLineRunner {

    private final SignatureService signatureService;

    @Override
    public void run(String... args) {
        String merchantId = "MERCH-002";
        String orderId = "ORD-20261019-001";
        BigDecimal amount = new BigDecimal("23000");

        String signature = signatureService.generateSignature(
                merchantId,
                orderId,
                amount
        );

        System.out.println("\n====================================");
        System.out.println("PAYGUARD SIGNATURE GENERATOR");
        System.out.println("Merchant ID : " + merchantId);
        System.out.println("Order ID    : " + orderId);
        System.out.println("Amount      : " + amount.toPlainString());
        System.out.println("Signature   : " + signature);
        System.out.println("====================================\n");
    }
}
