package tr.edu.etu.oyunport.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Yerel ödeme simülasyonu (rapor §1.2: gerçek banka entegrasyonu kapsam dışı).
 * Kart verisi alınmaz ve saklanmaz; demo için istemci beklenen sonucu seçer.
 * İsteğe bağlı gecikme, eşzamanlılık testlerinde yarış penceresini genişletmek içindir.
 */
@Component
public class PaymentSimulator {

    public enum SimulatedOutcome { SUCCESS, CARD_DECLINED, INSUFFICIENT_FUNDS }

    public record Decision(boolean approved, String failureReason) {}

    private final long latencyMs;

    public PaymentSimulator(@Value("${oyunport.payment.simulated-latency-ms:0}") long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Decision authorize(BigDecimal amount, SimulatedOutcome requested) {
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        SimulatedOutcome outcome = requested == null ? SimulatedOutcome.SUCCESS : requested;
        return switch (outcome) {
            case SUCCESS -> new Decision(true, null);
            case CARD_DECLINED -> new Decision(false, "Kart reddedildi (simülasyon).");
            case INSUFFICIENT_FUNDS -> new Decision(false, "Yetersiz bakiye (simülasyon).");
        };
    }
}
