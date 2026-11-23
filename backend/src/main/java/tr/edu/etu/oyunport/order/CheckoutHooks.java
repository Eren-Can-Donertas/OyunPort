package tr.edu.etu.oyunport.order;

/**
 * T2 içindeki ara noktalar. Üretimde boş (NONE) uygulama kullanılır; otomatik testler
 * rapor §8.4'teki "kalemlerin birinde kasıtlı hata üretip ROLLBACK sonrasında yarım kayıt
 * kalmadığını doğrulama" senaryosu için bu arayüzü bir test bean'i ile değiştirir.
 */
public interface CheckoutHooks {

    /** Her LIBRARY_ENTRY eklemesinden sonra, aynı transaction içinde çağrılır. */
    default void afterLibraryEntryInserted(long orderId, int insertedCount) {}

    CheckoutHooks NONE = new CheckoutHooks() {};
}
