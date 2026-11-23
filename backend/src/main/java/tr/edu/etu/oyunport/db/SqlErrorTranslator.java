package tr.edu.etu.oyunport.db;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tr.edu.etu.oyunport.common.ApiException;

import java.sql.SQLException;
import java.util.Map;

/**
 * PostgreSQL SQLSTATE kodlarını HTTP hatalarına çevirir. Veritabanı kısıtları
 * (UNIQUE, FK, CHECK, trigger) uygulamanın son savunma hattıdır; buradan gelen
 * hata mesajları kullanıcıya anlaşılır biçimde döner.
 */
@Component
public class SqlErrorTranslator {

    private static final Logger log = LoggerFactory.getLogger(SqlErrorTranslator.class);

    /** Bilinen kısıt adları için kullanıcı mesajları. */
    private static final Map<String, String> CONSTRAINT_MESSAGES = Map.ofEntries(
            Map.entry("uq_app_user_email", "Bu e-posta adresi zaten kayıtlı."),
            Map.entry("ck_app_user_email", "E-posta adresi geçerli değil."),
            Map.entry("ck_app_user_display_name", "Görünen ad 2-60 karakter olmalıdır."),
            Map.entry("uq_library_entry_user_product", "[BR02] Bu ürüne zaten sahipsiniz; aynı ürün ikinci kez edinilemez."),
            Map.entry("uq_library_entry_source_item", "Sipariş kalemi zaten bir kütüphane kaydına bağlı."),
            Map.entry("uq_store_order_checkout_key", "Bu checkout anahtarı başka bir siparişe ait."),
            Map.entry("uq_payment_attempt_request_key", "Bu ödeme isteği anahtarı (request_key) başka bir siparişe ait."),
            Map.entry("uq_one_successful_payment", "[BR09] Siparişin zaten başarılı bir ödemesi var."),
            Map.entry("uq_order_item_order_product", "[BR03] Siparişte aynı ürün iki kez bulunamaz."),
            Map.entry("uq_genre_name", "Bu tür adı zaten mevcut."),
            Map.entry("uq_play_session_one_open", "Bu oyun için zaten açık bir oynama oturumu var."),
            Map.entry("fk_review_ownership", "[BR06] Yalnızca sahip olduğunuz ana oyuna yorum yapabilirsiniz."),
            Map.entry("fk_review_base_game", "[BR06] Yorum yalnızca ana oyunlar (BASE_GAME) için yapılabilir."),
            Map.entry("ck_review_rating", "[BR06] Puan 1 ile 5 arasında olmalıdır."),
            Map.entry("ck_product_list_price", "Fiyat negatif olamaz."),
            Map.entry("ck_order_item_price", "Satış fiyatı negatif olamaz."),
            Map.entry("ck_payment_attempt_amount", "Ödeme tutarı negatif olamaz."),
            Map.entry("ck_play_session_interval", "Oturum bitişi başlangıçtan önce olamaz."),
            Map.entry("fk_product_genre_genre", "Tür başka ürünlerde kullanıldığı için silinemez veya bulunamadı."),
            Map.entry("fk_product_developer_developer", "Geliştirici başka ürünlerde kullanıldığı için silinemez veya bulunamadı."),
            Map.entry("fk_product_publisher", "Publisher ürünlerde kullanıldığı için silinemez veya bulunamadı."),
            Map.entry("fk_dlc_base_game", "[BR05] DLC yalnızca mevcut bir ana oyuna (BASE_GAME) bağlanabilir.")
    );

    public ApiException translate(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String constraint = null;
        String serverMessage = e.getMessage();
        if (e instanceof PSQLException pe && pe.getServerErrorMessage() != null) {
            ServerErrorMessage sem = pe.getServerErrorMessage();
            constraint = sem.getConstraint();
            serverMessage = sem.getMessage();
        }
        String known = constraint == null ? null : CONSTRAINT_MESSAGES.get(constraint);

        return switch (state) {
            case "23505" -> new ApiException(HttpStatus.CONFLICT, "UNIQUE_VIOLATION",
                    known != null ? known : "Benzersizlik kısıtı ihlali: " + constraint, e);
            case "23503" -> new ApiException(HttpStatus.CONFLICT, "FOREIGN_KEY_VIOLATION",
                    known != null ? known : "İlişkili kayıt kısıtı ihlali: " + constraint, e);
            // 23514: CHECK kısıtı veya [BRxx] trigger kuralı
            case "23514", "23000" -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INTEGRITY_RULE",
                    known != null ? known : serverMessage, e);
            case "23502" -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_NULL_VIOLATION",
                    "Zorunlu alan eksik: " + serverMessage, e);
            case "42501" -> new ApiException(HttpStatus.FORBIDDEN, "DB_PERMISSION_DENIED",
                    "Veritabanı rolü bu işleme yetkili değil.", e);
            case "40001", "40P01", "55P03" -> new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT",
                    "Eşzamanlı bir işlemle çakışıldı; lütfen tekrar deneyin.", e);
            default -> {
                if (state.startsWith("22")) {
                    yield new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATA", "Geçersiz veri: " + serverMessage, e);
                }
                log.error("Beklenmeyen SQL hatası state={} message={}", state, e.getMessage());
                yield new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DB_ERROR", "Veritabanı hatası.", e);
            }
        };
    }
}
