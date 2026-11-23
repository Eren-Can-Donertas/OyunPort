package tr.edu.etu.oyunport;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import tr.edu.etu.oyunport.support.Api;
import tr.edu.etu.oyunport.support.Fixtures;
import tr.edu.etu.oyunport.support.IntegrationTestBase;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Satın alma transaction'ları (FR04, FR05, FR06; BR02, BR03, BR04, BR05, BR07, BR08, BR09;
 * rapor §6.1 UC01/UC02, §7.1 ve §8.4 demo doğrulama senaryoları).
 */
class CheckoutIT extends IntegrationTestBase {

    private Map<String, Object> orderBody(UUID checkoutKey, long... productIds) {
        return Map.of("checkoutKey", checkoutKey.toString(),
                "productIds", java.util.Arrays.stream(productIds).boxed().toList());
    }

    private Map<String, Object> payBody(UUID requestKey, String outcome) {
        return Map.of("requestKey", requestKey.toString(), "simulateOutcome", outcome);
    }

    private long createOrder(Api.Session s, long... productIds) {
        Api.Resp r = api.post("/api/orders", s, orderBody(UUID.randomUUID(), productIds));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.body().at("/order/orderId").asLong();
    }

    private Api.Resp pay(Api.Session s, long orderId, UUID key, String outcome) {
        return api.post("/api/orders/" + orderId + "/payments", s, payBody(key, outcome));
    }

    private long count(String sql, Object... params) {
        return fx.scalarLong(sql, params);
    }

    // ------------------------------------------------------------------ UC01

    @Test
    @DisplayName("UC01: ana oyun + DLC sepeti T1 ve T2 ile COMPLETED olur; ödeme ve kütüphane kayıtları birlikte oluşur")
    void successfulPurchaseEndToEnd() {
        Fixtures.Catalog c = fx.catalog();
        long base = fx.baseGame(c, "Uçtan Uca Oyun", "100.00");
        long dlc = fx.dlc(c, base, "Uçtan Uca DLC", "30.00");
        Api.Session s = newCustomer();

        Api.Resp cart = api.post("/api/cart/validate", s, Map.of("productIds", List.of(base, dlc)));
        assertThat(cart.status()).isEqualTo(200);
        assertThat(cart.body().get("valid").asBoolean()).isTrue();
        assertThat(cart.body().get("total").decimalValue()).isEqualByComparingTo("130.00");

        UUID checkoutKey = UUID.randomUUID();
        Api.Resp created = api.post("/api/orders", s, orderBody(checkoutKey, base, dlc));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        JsonNode order = created.body().get("order");
        long orderId = order.get("orderId").asLong();
        assertThat(order.get("status").asText()).isEqualTo("PENDING");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("130.00");
        assertThat(order.get("items")).hasSize(2);
        assertThat(order.at("/items/0/lineNo").asInt()).isEqualTo(1);

        UUID requestKey = UUID.randomUUID();
        Api.Resp paid = pay(s, orderId, requestKey, "SUCCESS");
        assertThat(paid.status()).as(paid.toString()).isEqualTo(200);
        assertThat(paid.body().get("paymentStatus").asText()).isEqualTo("SUCCESS");
        assertThat(paid.body().get("orderStatus").asText()).isEqualTo("COMPLETED");
        assertThat(paid.body().get("amount").decimalValue()).isEqualByComparingTo("130.00");
        assertThat(paid.body().get("libraryIds")).hasSize(2);

        // Veritabanı durumu (şema sahibi ile doğrudan okunur)
        assertThat(fx.scalarString("SELECT status FROM store_order WHERE order_id = ?", orderId)).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM store_order WHERE order_id = ? AND completed_at IS NOT NULL", orderId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment_attempt WHERE order_id = ? AND status = 'SUCCESS' AND amount = 130.00", orderId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM library_entry WHERE order_id = ? AND user_id = ?", orderId, s.userId())).isEqualTo(2);
        assertThat(count("""
                SELECT count(*) FROM library_entry le JOIN order_item oi
                    ON oi.order_id = le.order_id AND oi.line_no = le.line_no AND oi.product_id = le.product_id
                 WHERE le.order_id = ?""", orderId)).isEqualTo(2);

        Api.Resp lib = api.get("/api/library", s);
        assertThat(lib.body()).hasSize(2);
        Api.Resp detail = api.get("/api/orders/" + orderId, s);
        assertThat(detail.body().get("payments")).hasSize(1);
        assertThat(detail.body().get("status").asText()).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------ UC02 / BR09

    @Test
    @DisplayName("UC02: başarısız ödeme FAILED olarak saklanır, sipariş PENDING kalır, kütüphane oluşmaz; yeni request_key ile yeniden deneme başarılı")
    void failedPaymentThenRetry() {
        Fixtures.Catalog c = fx.catalog();
        long base = fx.baseGame(c, "Başarısız Ödeme Oyunu", "250.00");
        Api.Session s = newCustomer();
        long orderId = createOrder(s, base);

        Api.Resp failed = pay(s, orderId, UUID.randomUUID(), "CARD_DECLINED");
        assertThat(failed.status()).isEqualTo(200);
        assertThat(failed.body().get("paymentStatus").asText()).isEqualTo("FAILED");
        assertThat(failed.body().get("orderStatus").asText()).isEqualTo("PENDING");
        assertThat(failed.body().get("failureReason").asText()).contains("reddedildi");
        assertThat(fx.scalarString("SELECT status FROM store_order WHERE order_id = ?", orderId)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM payment_attempt WHERE order_id = ? AND status = 'FAILED'", orderId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM library_entry WHERE order_id = ?", orderId)).isZero();

        Api.Resp ok = pay(s, orderId, UUID.randomUUID(), "SUCCESS");
        assertThat(ok.body().get("paymentStatus").asText()).isEqualTo("SUCCESS");
        assertThat(ok.body().get("orderStatus").asText()).isEqualTo("COMPLETED");
        // Geçmiş korunur: FAILED + SUCCESS
        assertThat(fx.column("SELECT status FROM payment_attempt WHERE order_id = ? ORDER BY payment_id", orderId))
                .containsExactly("FAILED", "SUCCESS");
        assertThat(count("SELECT count(*) FROM library_entry WHERE order_id = ?", orderId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ Idempotency

    @Test
    @DisplayName("Idempotency: aynı request_key tekrar gönderildiğinde yeni deneme oluşmaz, önceki sonuç döner (SUCCESS ve FAILED)")
    void paymentRequestKeyIdempotency() {
        Fixtures.Catalog c = fx.catalog();
        long g1 = fx.baseGame(c, "Idem Oyun 1", "80.00");
        long g2 = fx.baseGame(c, "Idem Oyun 2", "90.00");
        Api.Session s = newCustomer();

        long o1 = createOrder(s, g1);
        UUID k1 = UUID.randomUUID();
        Api.Resp first = pay(s, o1, k1, "SUCCESS");
        Api.Resp replay = pay(s, o1, k1, "SUCCESS");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("replayed").asBoolean()).isTrue();
        assertThat(replay.body().get("paymentId").asLong()).isEqualTo(first.body().get("paymentId").asLong());
        assertThat(replay.body().get("libraryIds")).hasSize(1);
        assertThat(count("SELECT count(*) FROM payment_attempt WHERE order_id = ?", o1)).isEqualTo(1);

        long o2 = createOrder(s, g2);
        UUID k2 = UUID.randomUUID();
        Api.Resp f = pay(s, o2, k2, "INSUFFICIENT_FUNDS");
        Api.Resp fReplay = pay(s, o2, k2, "SUCCESS");  // aynı anahtar: sonuç değişmez
        assertThat(fReplay.body().get("paymentStatus").asText()).isEqualTo("FAILED");
        assertThat(fReplay.body().get("replayed").asBoolean()).isTrue();
        assertThat(fReplay.body().get("paymentId").asLong()).isEqualTo(f.body().get("paymentId").asLong());
        assertThat(count("SELECT count(*) FROM payment_attempt WHERE order_id = ?", o2)).isEqualTo(1);
        assertThat(fx.scalarString("SELECT status FROM store_order WHERE order_id = ?", o2)).isEqualTo("PENDING");

        // Aynı request_key başka sipariş için kullanılamaz
        Api.Resp reused = pay(s, o2, k1, "SUCCESS");
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.code()).isEqualTo("REQUEST_KEY_REUSED");
    }

    @Test
    @DisplayName("Idempotency: aynı checkout_key ile T1 tekrarlanırsa ikinci sipariş oluşmaz")
    void checkoutKeyIdempotency() {
        Fixtures.Catalog c = fx.catalog();
        long g = fx.baseGame(c, "Checkout Key Oyunu", "60.00");
        Api.Session s = newCustomer();
        UUID key = UUID.randomUUID();

        Api.Resp a = api.post("/api/orders", s, orderBody(key, g));
        Api.Resp b = api.post("/api/orders", s, orderBody(key, g));
        assertThat(a.status()).isEqualTo(201);
        assertThat(b.status()).isEqualTo(200);
        assertThat(b.body().get("replayed").asBoolean()).isTrue();
        assertThat(b.body().at("/order/orderId").asLong()).isEqualTo(a.body().at("/order/orderId").asLong());
        assertThat(count("SELECT count(*) FROM store_order WHERE checkout_key = ?::uuid", key.toString())).isEqualTo(1);

        // Başka kullanıcı aynı checkout_key'i kullanamaz (UNIQUE, RLS altında görünmez -> 409)
        Api.Session other = newCustomer();
        Api.Resp clash = api.post("/api/orders", other, orderBody(key, g));
        assertThat(clash.status()).isEqualTo(409);
    }

    // ------------------------------------------------------------------ BR05 DLC

    @Test
    @DisplayName("BR05: ana oyun olmadan DLC sepeti ve siparişi reddedilir; ana oyun + DLC sepeti kabul edilir")
    void dlcRequiresBaseGame() {
        Fixtures.Catalog c = fx.catalog();
        long base = fx.baseGame(c, "DLC Kural Oyunu", "120.00");
        long dlc = fx.dlc(c, base, "DLC Kural Paketi", "40.00");
        Api.Session s = newCustomer();

        Api.Resp cart = api.post("/api/cart/validate", s, Map.of("productIds", List.of(dlc)));
        assertThat(cart.body().get("valid").asBoolean()).isFalse();
        assertThat(cart.body().at("/items/0/status").asText()).isEqualTo("DLC_REQUIRES_BASE_GAME");

        long before = count("SELECT count(*) FROM store_order WHERE user_id = ?", s.userId());
        Api.Resp rejected = api.post("/api/orders", s, orderBody(UUID.randomUUID(), dlc));
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.code()).isEqualTo("DLC_REQUIRES_BASE_GAME");
        assertThat(count("SELECT count(*) FROM store_order WHERE user_id = ?", s.userId())).isEqualTo(before);

        // DLC sepette ana oyundan önce olsa da kabul edilir (deferred trigger commit anında denetler)
        long orderId = createOrder(s, dlc, base);
        assertThat(pay(s, orderId, UUID.randomUUID(), "SUCCESS").body().get("orderStatus").asText()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM library_entry WHERE user_id = ?", s.userId())).isEqualTo(2);
    }

    @Test
    @DisplayName("BR05: ana oyuna önceden sahip olan müşteri DLC'yi tek başına satın alabilir")
    void dlcAfterOwningBaseGame() {
        Fixtures.Catalog c = fx.catalog();
        long base = fx.baseGame(c, "Önce Ana Oyun", "100.00");
        long dlc = fx.dlc(c, base, "Sonra DLC", "25.00");
        Api.Session s = newCustomer();
        pay(s, createOrder(s, base), UUID.randomUUID(), "SUCCESS");
        Api.Resp r = pay(s, createOrder(s, dlc), UUID.randomUUID(), "SUCCESS");
        assertThat(r.body().get("orderStatus").asText()).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------ BR02 çift satın alma

    @Test
    @DisplayName("BR02: sahip olunan ürün için yeni sipariş (T1) reddedilir")
    void doublePurchaseRejectedAtT1() {
        Fixtures.Catalog c = fx.catalog();
        long g = fx.baseGame(c, "Çift Satın Alma", "70.00");
        Api.Session s = newCustomer();
        pay(s, createOrder(s, g), UUID.randomUUID(), "SUCCESS");

        Api.Resp again = api.post("/api/orders", s, orderBody(UUID.randomUUID(), g));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ALREADY_OWNED");
        Api.Resp cart = api.post("/api/cart/validate", s, Map.of("productIds", List.of(g)));
        assertThat(cart.body().at("/items/0/status").asText()).isEqualTo("OWNED");
    }

    @Test
    @DisplayName("BR02: aynı ürünü içeren iki PENDING siparişten ikincisinin ödemesi reddedilir ve hiçbir kayıt yazılmaz")
    void secondPendingOrderWithSameProductRejected() {
        Fixtures.Catalog c = fx.catalog();
        long g = fx.baseGame(c, "İki Bekleyen Sipariş", "55.00");
        Api.Session s = newCustomer();
        long o1 = createOrder(s, g);
        long o2 = createOrder(s, g);

        assertThat(pay(s, o1, UUID.randomUUID(), "SUCCESS").body().get("orderStatus").asText()).isEqualTo("COMPLETED");
        Api.Resp second = pay(s, o2, UUID.randomUUID(), "SUCCESS");
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("ALREADY_OWNED");
        assertThat(fx.scalarString("SELECT status FROM store_order WHERE order_id = ?", o2)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM payment_attempt WHERE order_id = ?", o2)).isZero();
        assertThat(count("SELECT count(*) FROM library_entry WHERE user_id = ? AND product_id = ?", s.userId(), g)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ ROLLBACK

    @Test
    @DisplayName("§8.4 ROLLBACK: T2'de ikinci kalemden önce kasıtlı hata -> ödeme, kütüphane ve durum değişikliği kalmaz")
    void rollbackLeavesNoPartialRecords() {
        Fixtures.Catalog c = fx.catalog();
        long base = fx.baseGame(c, "Rollback Oyunu", "150.00");
        long dlc = fx.dlc(c, base, "Rollback DLC", "45.00");
        Api.Session s = newCustomer();
        long orderId = createOrder(s, base, dlc);

        hooks.failAfterLibraryEntries(orderId, 1);   // 1. LIBRARY_ENTRY eklendikten sonra hata
        Api.Resp r = pay(s, orderId, UUID.randomUUID(), "SUCCESS");
        assertThat(r.status()).isEqualTo(500);

        assertThat(fx.scalarString("SELECT status FROM store_order WHERE order_id = ?", orderId)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM store_order WHERE order_id = ? AND completed_at IS NULL", orderId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment_attempt WHERE order_id = ?", orderId)).isZero();
        assertThat(count("SELECT count(*) FROM library_entry WHERE order_id = ?", orderId)).isZero();
        assertThat(count("SELECT count(*) FROM library_entry WHERE user_id = ?", s.userId())).isZero();

        hooks.reset();
        Api.Resp retry = pay(s, orderId, UUID.randomUUID(), "SUCCESS");
        assertThat(retry.body().get("orderStatus").asText()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM library_entry WHERE order_id = ?", orderId)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ Eşzamanlılık

    @Test
    @DisplayName("§8.4 Eşzamanlılık: aynı kullanıcının aynı ürünü içeren iki eşzamanlı ödemesi APP_USER kilidiyle sıralanır; tek sahiplik oluşur")
    void concurrentPaymentsSerializedByUserLock() throws Exception {
        Fixtures.Catalog c = fx.catalog();
        long g = fx.baseGame(c, "Eşzamanlı Oyun", "99.00");
        long other = fx.baseGame(c, "Eşzamanlı Diğer", "10.00");
        Api.Session s = newCustomer();
        long o1 = createOrder(s, g);
        long o2 = createOrder(s, g, other);

        CountDownLatch aInside = new CountDownLatch(1);
        AtomicBoolean sawWaiter = new AtomicBoolean(false);
        hooks.onLibraryEntry(o1, (oid, n) -> {
            aInside.countDown();
            // B'nin kullanıcı satırı kilidinde beklediğini pg_locks üzerinden gör, sonra devam et
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                if (fx.scalarLong("SELECT count(*) FROM pg_locks WHERE NOT granted") > 0) {
                    sawWaiter.set(true);
                    break;
                }
                try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });

        CompletableFuture<Api.Resp> a = CompletableFuture.supplyAsync(() -> pay(s, o1, UUID.randomUUID(), "SUCCESS"));
        assertThat(aInside.await(15, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Api.Resp> b = CompletableFuture.supplyAsync(() -> pay(s, o2, UUID.randomUUID(), "SUCCESS"));

        Api.Resp ra = a.get(30, TimeUnit.SECONDS);
        Api.Resp rb = b.get(30, TimeUnit.SECONDS);
        assertThat(sawWaiter.get()).as("İkinci ödeme kilitte beklemeli").isTrue();
        assertThat(ra.body().get("orderStatus").asText()).isEqualTo("COMPLETED");
        assertThat(rb.status()).isEqualTo(409);
        assertThat(rb.code()).isEqualTo("ALREADY_OWNED");
        assertThat(count("SELECT count(*) FROM library_entry WHERE user_id = ? AND product_id = ?", s.userId(), g)).isEqualTo(1);
        assertThat(fx.scalarString("SELECT status FROM store_order WHERE order_id = ?", o2)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM payment_attempt WHERE order_id = ?", o2)).isZero();
    }

    @Test
    @DisplayName("BR02 son savunma: servis kilidi atlansa bile UNIQUE(user_id, product_id) ikinci eşzamanlı edinimi reddeder")
    void uniqueConstraintIsLastLineOfDefence() throws Exception {
        Fixtures.Catalog c = fx.catalog();
        long g = fx.baseGame(c, "UNIQUE Savunma", "33.00");
        Api.Session s = newCustomer();
        long o1 = createOrder(s, g);
        long o2 = createOrder(s, g);

        try (Connection c1 = dbAdmin(); Connection c2 = dbAdmin()) {
            c1.setAutoCommit(false);
            c2.setAutoCommit(false);
            completeDirect(c1, s.userId(), o1, g);   // commit edilmedi, unique indeks satırı kilitli

            CompletableFuture<SQLException> second = CompletableFuture.supplyAsync(() -> {
                try {
                    completeDirect(c2, s.userId(), o2, g);   // c1 commit edene kadar bekler
                    c2.commit();
                    return null;
                } catch (SQLException e) {
                    try { c2.rollback(); } catch (SQLException ignored) { }
                    return e;
                }
            });
            Thread.sleep(500);
            assertThat(second.isDone()).as("ikinci transaction unique indekste beklemeli").isFalse();
            c1.commit();
            SQLException err = second.get(15, TimeUnit.SECONDS);
            assertThat((Throwable) err).isInstanceOf(PSQLException.class);
            assertThat(err.getSQLState()).isEqualTo("23505");
            assertThat(((PSQLException) err).getServerErrorMessage().getConstraint()).isEqualTo("uq_library_entry_user_product");
        }
        assertThat(count("SELECT count(*) FROM library_entry WHERE user_id = ? AND product_id = ?", s.userId(), g)).isEqualTo(1);
        assertThat(fx.scalarString("SELECT status FROM store_order WHERE order_id = ?", o2)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM payment_attempt WHERE order_id = ?", o2)).isZero();
    }

    private static void completeDirect(Connection c, long userId, long orderId, long productId) throws SQLException {
        Fixtures.exec(c, "INSERT INTO payment_attempt (order_id, amount, status, request_key) "
                + "SELECT ?, sum(unit_price_paid), 'SUCCESS', gen_random_uuid() FROM order_item WHERE order_id = ?", orderId, orderId);
        Fixtures.exec(c, "INSERT INTO library_entry (user_id, product_id, order_id, line_no) VALUES (?, ?, ?, 1)",
                userId, productId, orderId);
        Fixtures.exec(c, "UPDATE store_order SET status = 'COMPLETED', completed_at = now() WHERE order_id = ?", orderId);
    }

    // ------------------------------------------------------------------ BR07, BR08, iptal

    @Test
    @DisplayName("BR07: T1 sonrası fiyat değişse de sipariş ve ödeme satın alma anındaki fiyatı kullanır")
    void priceChangeDoesNotAffectPlacedOrder() {
        Fixtures.Catalog c = fx.catalog();
        long g = fx.baseGame(c, "Fiyatı Değişen Oyun", "200.00");
        Api.Session s = newCustomer();
        long orderId = createOrder(s, g);
        fx.sql("UPDATE product SET list_price = 299.00 WHERE product_id = ?", g);

        Api.Resp r = pay(s, orderId, UUID.randomUUID(), "SUCCESS");
        assertThat(r.body().get("amount").decimalValue()).isEqualByComparingTo("200.00");
        JsonNode item = api.get("/api/orders/" + orderId, s).body().at("/items/0");
        assertThat(item.get("unitPricePaid").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(item.get("currentListPrice").decimalValue()).isEqualByComparingTo("299.00");
    }

    @Test
    @DisplayName("BR08: tamamlanmış sipariş tekrar ödenemez; PENDING sipariş iptal edilir ve iptal edilen sipariş ödenemez")
    void completedAndCancelledOrdersAreFinal() {
        Fixtures.Catalog c = fx.catalog();
        long g1 = fx.baseGame(c, "Final Oyun 1", "10.00");
        long g2 = fx.baseGame(c, "Final Oyun 2", "20.00");
        Api.Session s = newCustomer();

        long done = createOrder(s, g1);
        pay(s, done, UUID.randomUUID(), "SUCCESS");
        Api.Resp again = pay(s, done, UUID.randomUUID(), "SUCCESS");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ORDER_ALREADY_COMPLETED");
        assertThat(api.post("/api/orders/" + done + "/cancel", s, null).status()).isEqualTo(409);

        long pending = createOrder(s, g2);
        Api.Resp cancelled = api.post("/api/orders/" + pending + "/cancel", s, null);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("CANCELLED");
        Api.Resp payCancelled = pay(s, pending, UUID.randomUUID(), "SUCCESS");
        assertThat(payCancelled.status()).isEqualTo(409);
        assertThat(payCancelled.code()).isEqualTo("ORDER_CANCELLED");
    }

    @Test
    @DisplayName("Sepet doğrulaması: sahip olunan, pasif, tekrarlanan ve bulunmayan ürünler işaretlenir; pasif ürün T1'de reddedilir")
    void cartValidationStatuses() {
        Fixtures.Catalog c = fx.catalog();
        long owned = fx.baseGame(c, "Sahip Olunan", "15.00");
        long inactive = fx.baseGame(c, "Pasif Ürün", "15.00");
        long ok = fx.baseGame(c, "Uygun Ürün", "15.00");
        fx.sql("UPDATE product SET is_active = false WHERE product_id = ?", inactive);
        Api.Session s = newCustomer();
        pay(s, createOrder(s, owned), UUID.randomUUID(), "SUCCESS");

        Api.Resp r = api.post("/api/cart/validate", s, Map.of("productIds", List.of(ok, owned, inactive, ok, 987654321L)));
        assertThat(r.body().get("valid").asBoolean()).isFalse();
        List<String> statuses = new java.util.ArrayList<>();
        r.body().get("items").forEach(i -> statuses.add(i.get("status").asText()));
        assertThat(statuses).containsExactly("OK", "OWNED", "INACTIVE", "DUPLICATE", "NOT_FOUND");
        assertThat(r.body().get("total").decimalValue()).isEqualByComparingTo("15.00");

        Api.Resp t1 = api.post("/api/orders", s, orderBody(UUID.randomUUID(), inactive));
        assertThat(t1.status()).isEqualTo(422);
        assertThat(t1.code()).isEqualTo("PRODUCT_INACTIVE");
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 26).boxed().toList();
        assertThat(api.post("/api/cart/validate", s, Map.of("productIds", tooMany)).status()).isEqualTo(400);
        Api.Resp dup = api.post("/api/orders", s, orderBody(UUID.randomUUID(), ok, ok));
        assertThat(dup.status()).isEqualTo(422);
        assertThat(dup.code()).isEqualTo("DUPLICATE_PRODUCT");
    }

    @Test
    @DisplayName("Yetki: müşteri başka kullanıcının siparişini göremez ve ödeyemez (RLS -> 404)")
    void cannotAccessOtherUsersOrder() {
        Fixtures.Catalog c = fx.catalog();
        long g = fx.baseGame(c, "Başkasının Siparişi", "42.00");
        Api.Session owner = newCustomer();
        Api.Session intruder = newCustomer();
        long orderId = createOrder(owner, g);

        assertThat(api.get("/api/orders/" + orderId, intruder).status()).isEqualTo(404);
        assertThat(pay(intruder, orderId, UUID.randomUUID(), "SUCCESS").status()).isEqualTo(404);
        assertThat(api.post("/api/orders/" + orderId + "/cancel", intruder, null).status()).isEqualTo(404);
        assertThat(api.get("/api/orders", intruder).body().get("total").asLong()).isZero();
        assertThat(fx.scalarString("SELECT status FROM store_order WHERE order_id = ?", orderId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Girişsiz kullanıcı sipariş oluşturamaz (401)")
    void anonymousCannotOrder() {
        Api.Resp r = api.postAnon("/api/orders", orderBody(UUID.randomUUID(), 1L));
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("Ödeme tutarı veritabanında kalemlerden doğrulanır: yanlış tutar trigger ile reddedilir")
    void paymentAmountMustMatchItems() {
        Fixtures.Catalog c = fx.catalog();
        long g = fx.baseGame(c, "Tutar Kontrol", "75.50");
        Api.Session s = newCustomer();
        long orderId = createOrder(s, g);
        assertThatThrownBy(() -> fx.sql(
                "INSERT INTO payment_attempt (order_id, amount, status, request_key) VALUES (?, 1.00, 'FAILED', gen_random_uuid())", orderId))
                .hasMessageContaining("[BR04]");
        assertThat(count("SELECT count(*) FROM payment_attempt WHERE order_id = ?", orderId)).isZero();
        assertThat(new BigDecimal(fx.scalarString("SELECT sum(unit_price_paid) FROM order_item WHERE order_id = ?", orderId)))
                .isEqualByComparingTo("75.50");
    }
}
