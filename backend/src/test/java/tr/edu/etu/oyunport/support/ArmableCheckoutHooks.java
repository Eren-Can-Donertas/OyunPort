package tr.edu.etu.oyunport.support;

import tr.edu.etu.oyunport.order.CheckoutHooks;

import java.util.function.BiConsumer;

/**
 * Test için T2 ara noktası. Belirli bir sipariş için N. LIBRARY_ENTRY eklemesinden sonra
 * kasıtlı hata fırlatabilir veya bir eylem (ör. eşzamanlılık senkronizasyonu) çalıştırabilir.
 */
public class ArmableCheckoutHooks implements CheckoutHooks {

    private volatile Long targetOrderId;
    private volatile Integer failAfter;
    private volatile BiConsumer<Long, Integer> action;

    public void failAfterLibraryEntries(long orderId, int count) {
        this.targetOrderId = orderId;
        this.failAfter = count;
    }

    public void onLibraryEntry(long orderId, BiConsumer<Long, Integer> a) {
        this.targetOrderId = orderId;
        this.action = a;
    }

    public void reset() {
        targetOrderId = null;
        failAfter = null;
        action = null;
    }

    @Override
    public void afterLibraryEntryInserted(long orderId, int insertedCount) {
        Long target = targetOrderId;
        if (target == null || target != orderId) {
            return;
        }
        BiConsumer<Long, Integer> a = action;
        if (a != null) {
            a.accept(orderId, insertedCount);
        }
        Integer f = failAfter;
        if (f != null && insertedCount >= f) {
            throw new IllegalStateException("TEST: " + insertedCount + ". kütüphane kaydından sonra kasıtlı hata");
        }
    }
}
