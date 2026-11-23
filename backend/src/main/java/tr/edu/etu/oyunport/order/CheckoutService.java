package tr.edu.etu.oyunport.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tr.edu.etu.oyunport.common.ApiException;
import tr.edu.etu.oyunport.db.Db;
import tr.edu.etu.oyunport.db.Tx;
import tr.edu.etu.oyunport.order.OrderModels.CreateOrderResult;
import tr.edu.etu.oyunport.order.OrderModels.PaymentResult;
import tr.edu.etu.oyunport.order.PaymentSimulator.SimulatedOutcome;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Satın alma akışı (FR05, FR06; rapor §7.1). İki kontrollü transaction:
 *
 * <p><b>T1 — sipariş oluşturma</b>: kullanıcı satırı kilitlenir, checkout_key ile idempotency,
 * ürün/fiyat/aktiflik, sahiplik (BR02) ve DLC ön koşulu (BR05) doğrulanır;
 * STORE_ORDER(PENDING) ve en az bir ORDER_ITEM aynı transaction'da yazılır. Fiyat
 * sunucuda list_price'tan alınır (BR07).
 *
 * <p><b>T2 — ödeme tamamlama</b>: kullanıcı ve sipariş satırları FOR UPDATE ile kilitlenir,
 * request_key ile idempotency, PENDING/sahip kontrolü, sahiplik ve DLC ön koşulu tekrar
 * doğrulanır; ödeme simülasyonu çalışır.
 * <ul>
 *   <li>FAILED: yalnızca PAYMENT_ATTEMPT(FAILED) yazılır ve commit edilir; sipariş PENDING kalır.</li>
 *   <li>SUCCESS: PAYMENT_ATTEMPT(SUCCESS) + her kalem için LIBRARY_ENTRY + STORE_ORDER(COMPLETED)
 *       tek transaction'da atomik yazılır.</li>
 *   <li>Teknik/kısıt hatası: T2'nin tüm değişiklikleri ROLLBACK edilir.</li>
 * </ul>
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    public static final int MAX_ITEMS_PER_ORDER = 25;

    private final Db db;
    private final OrderQueries queries;
    private final PaymentSimulator simulator;
    private final CheckoutHooks hooks;

    public CheckoutService(Db db, OrderQueries queries, PaymentSimulator simulator,
                           ObjectProvider<CheckoutHooks> hooks) {
        this.db = db;
        this.queries = queries;
        this.simulator = simulator;
        this.hooks = hooks.getIfAvailable(() -> CheckoutHooks.NONE);
    }

    record ProductRow(long productId, String title, BigDecimal listPrice, boolean active,
                      boolean baseGame, Long baseGameId) {}

    record ItemRow(int lineNo, long productId, BigDecimal unitPricePaid, Long dlcBaseGameId) {}

    record InsertedPayment(long id, Instant at) {}

    // =====================================================================
    // T1
    // =====================================================================
    public CreateOrderResult createOrder(long userId, UUID checkoutKey, List<Long> productIds) {
        if (checkoutKey == null) {
            throw ApiException.badRequest("checkoutKey zorunludur.");
        }
        if (productIds == null || productIds.isEmpty()) {
            throw ApiException.unprocessable("EMPTY_CART", "[BR04] Sipariş en az bir ürün içermelidir.");
        }
        if (productIds.size() > MAX_ITEMS_PER_ORDER) {
            throw ApiException.unprocessable("TOO_MANY_ITEMS", "Bir siparişte en fazla " + MAX_ITEMS_PER_ORDER + " ürün olabilir.");
        }
        if (new HashSet<>(productIds).size() != productIds.size()) {
            throw ApiException.unprocessable("DUPLICATE_PRODUCT", "[BR03] Siparişte aynı ürün iki kez bulunamaz.");
        }

        return db.customer(userId, tx -> {
            log.info("T1 BEGIN user={} checkoutKey={} products={}", userId, checkoutKey, productIds);
            lockUser(tx, userId);

            Optional<Long> existing = tx.queryOne(
                    "SELECT order_id FROM store_order WHERE checkout_key = ?", rs -> rs.getLong(1), checkoutKey);
            if (existing.isPresent()) {
                log.info("T1 idempotent replay order={}", existing.get());
                return new CreateOrderResult(true, queries.findDetail(tx, existing.get()).orElseThrow());
            }

            Map<Long, ProductRow> products = loadProducts(tx, productIds);
            for (Long pid : productIds) {
                ProductRow p = products.get(pid);
                if (p == null) {
                    throw ApiException.unprocessable("PRODUCT_NOT_FOUND", "Ürün bulunamadı: " + pid);
                }
                if (!p.active()) {
                    throw ApiException.unprocessable("PRODUCT_INACTIVE", "Ürün satışta değil (pasif): " + p.title());
                }
            }
            assertNotOwned(tx, userId, productIds, products);
            assertDlcPrerequisites(tx, userId, productIds, products);

            long orderId = tx.insertReturningLong(
                    "INSERT INTO store_order (user_id, status, checkout_key) VALUES (?, 'PENDING', ?) RETURNING order_id",
                    userId, checkoutKey);
            int lineNo = 1;
            for (Long pid : productIds) {
                // BR07: satın alma anındaki fiyat sunucuda list_price'tan alınır; istemci fiyatı kullanılmaz.
                tx.update("INSERT INTO order_item (order_id, line_no, product_id, unit_price_paid) VALUES (?, ?, ?, ?)",
                        orderId, lineNo++, pid, products.get(pid).listPrice());
            }
            log.info("T1 COMMIT order={} items={}", orderId, productIds.size());
            return new CreateOrderResult(false, queries.findDetail(tx, orderId).orElseThrow());
        });
    }

    // =====================================================================
    // T2
    // =====================================================================
    public PaymentResult pay(long userId, long orderId, UUID requestKey, SimulatedOutcome outcome) {
        if (requestKey == null) {
            throw ApiException.badRequest("requestKey zorunludur.");
        }
        return db.customer(userId, tx -> {
            log.info("T2 BEGIN user={} order={} requestKey={} outcome={}", userId, orderId, requestKey, outcome);
            // Aynı kullanıcının paralel checkout işlemlerini sırala (rapor §7.1).
            lockUser(tx, userId);

            // Idempotency: request_key daha önce işlendiyse önceki sonucu döndür.
            Optional<PaymentResult> replay = findPaymentByRequestKey(tx, requestKey, orderId);
            if (replay.isPresent()) {
                log.info("T2 idempotent replay payment={}", replay.get().paymentId());
                return replay.get();
            }

            // Sipariş satırını kilitle; RLS yalnızca oturum sahibinin siparişini gösterir.
            String status = tx.queryOne("SELECT status FROM store_order WHERE order_id = ? FOR UPDATE",
                            rs -> rs.getString(1), orderId)
                    .orElseThrow(() -> ApiException.notFound("Sipariş bulunamadı."));
            if ("COMPLETED".equals(status)) {
                throw ApiException.conflict("ORDER_ALREADY_COMPLETED", "[BR08] Sipariş zaten tamamlanmış.");
            }
            if ("CANCELLED".equals(status)) {
                throw ApiException.conflict("ORDER_CANCELLED", "İptal edilmiş sipariş için ödeme yapılamaz.");
            }

            List<ItemRow> items = tx.query("""
                    SELECT oi.line_no, oi.product_id, oi.unit_price_paid, d.base_game_id
                      FROM order_item oi
                      LEFT JOIN dlc d ON d.product_id = oi.product_id
                     WHERE oi.order_id = ?
                     ORDER BY oi.line_no
                    """, rs -> new ItemRow(rs.getInt("line_no"), rs.getLong("product_id"),
                    rs.getBigDecimal("unit_price_paid"), Tx.getLongOrNull(rs, "base_game_id")), orderId);
            if (items.isEmpty()) {
                throw ApiException.unprocessable("EMPTY_ORDER", "[BR04] Siparişte kalem yok.");
            }

            // Sahiplik ve DLC ön koşulunu kilit altında tekrar doğrula.
            List<Long> pids = items.stream().map(ItemRow::productId).toList();
            List<Long> owned = tx.query(
                    "SELECT product_id FROM library_entry WHERE user_id = ? AND product_id = ANY(?) ORDER BY product_id",
                    rs -> rs.getLong(1), userId, pids);
            if (!owned.isEmpty()) {
                throw ApiException.conflict("ALREADY_OWNED",
                        "[BR02] Siparişteki şu ürünlere zaten sahipsiniz: " + owned + ". Siparişi iptal edip yeni sepet oluşturun.");
            }
            Set<Long> inOrder = new HashSet<>(pids);
            for (ItemRow it : items) {
                if (it.dlcBaseGameId() != null && !inOrder.contains(it.dlcBaseGameId())
                        && !ownsProduct(tx, userId, it.dlcBaseGameId())) {
                    throw ApiException.unprocessable("DLC_REQUIRES_BASE_GAME",
                            "[BR05] DLC " + it.productId() + " için ana oyun " + it.dlcBaseGameId() + " gereklidir.");
                }
            }

            // Tutar sipariş kalemlerinden hesaplanır (BR04); istemciden alınmaz.
            BigDecimal amount = items.stream().map(ItemRow::unitPricePaid).reduce(BigDecimal.ZERO, BigDecimal::add);
            PaymentSimulator.Decision decision = simulator.authorize(amount, outcome);

            if (!decision.approved()) {
                InsertedPayment p = tx.queryOne("""
                        INSERT INTO payment_attempt (order_id, amount, status, request_key)
                        VALUES (?, ?, 'FAILED', ?) RETURNING payment_id, attempted_at
                        """, rs -> new InsertedPayment(rs.getLong(1), Tx.getInstant(rs, "attempted_at")),
                        orderId, amount, requestKey).orElseThrow();
                log.info("T2 COMMIT (FAILED kaydı) order={} payment={}", orderId, p.id());
                return new PaymentResult(p.id(), orderId, "FAILED", "PENDING", amount, p.at(), requestKey,
                        false, decision.failureReason(), List.of());
            }

            InsertedPayment p = tx.queryOne("""
                    INSERT INTO payment_attempt (order_id, amount, status, request_key)
                    VALUES (?, ?, 'SUCCESS', ?) RETURNING payment_id, attempted_at
                    """, rs -> new InsertedPayment(rs.getLong(1), Tx.getInstant(rs, "attempted_at")),
                    orderId, amount, requestKey).orElseThrow();

            List<Long> libraryIds = new ArrayList<>();
            for (ItemRow it : items) {
                long libraryId = tx.insertReturningLong("""
                        INSERT INTO library_entry (user_id, product_id, order_id, line_no, acquired_at)
                        VALUES (?, ?, ?, ?, now()) RETURNING library_id
                        """, userId, it.productId(), orderId, it.lineNo());
                libraryIds.add(libraryId);
                hooks.afterLibraryEntryInserted(orderId, libraryIds.size());
            }

            int updated = tx.update("""
                    UPDATE store_order SET status = 'COMPLETED', completed_at = now()
                     WHERE order_id = ? AND status = 'PENDING'
                    """, orderId);
            if (updated != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "ORDER_STATE_CHANGED", "Sipariş durumu eşzamanlı olarak değişti.");
            }
            log.info("T2 COMMIT (SUCCESS) order={} payment={} libraryEntries={}", orderId, p.id(), libraryIds.size());
            return new PaymentResult(p.id(), orderId, "SUCCESS", "COMPLETED", amount, p.at(), requestKey,
                    false, null, libraryIds);
        });
    }

    // =====================================================================
    // PENDING siparişi iptal (Tasarım notları Ç2)
    // =====================================================================
    public void cancel(long userId, long orderId) {
        db.customer(userId, tx -> {
            lockUser(tx, userId);
            String status = tx.queryOne("SELECT status FROM store_order WHERE order_id = ? FOR UPDATE",
                            rs -> rs.getString(1), orderId)
                    .orElseThrow(() -> ApiException.notFound("Sipariş bulunamadı."));
            if (!"PENDING".equals(status)) {
                throw ApiException.conflict("ORDER_NOT_PENDING", "[BR08] Yalnızca PENDING sipariş iptal edilebilir (durum: " + status + ").");
            }
            tx.update("UPDATE store_order SET status = 'CANCELLED' WHERE order_id = ?", orderId);
            log.info("Sipariş iptal edildi order={} user={}", orderId, userId);
            return null;
        });
    }

    // =====================================================================
    // Yardımcılar
    // =====================================================================
    private static void lockUser(Tx tx, long userId) throws SQLException {
        tx.queryOne("SELECT user_id FROM app_user WHERE user_id = ? AND is_active FOR UPDATE",
                        rs -> rs.getLong(1), userId)
                .orElseThrow(() -> ApiException.forbidden("Kullanıcı bulunamadı veya pasif."));
    }

    private Optional<PaymentResult> findPaymentByRequestKey(Tx tx, UUID requestKey, long orderId) throws SQLException {
        record Prev(long paymentId, long orderId, String status, BigDecimal amount, Instant attemptedAt) {}
        Optional<Prev> prev = tx.queryOne("""
                SELECT payment_id, order_id, status, amount, attempted_at
                  FROM payment_attempt WHERE request_key = ?
                """, rs -> new Prev(rs.getLong("payment_id"), rs.getLong("order_id"), rs.getString("status"),
                rs.getBigDecimal("amount"), Tx.getInstant(rs, "attempted_at")), requestKey);
        if (prev.isEmpty()) {
            return Optional.empty();
        }
        Prev p = prev.get();
        if (p.orderId() != orderId) {
            throw ApiException.conflict("REQUEST_KEY_REUSED", "Bu request_key başka bir sipariş için kullanılmış.");
        }
        String orderStatus = tx.queryOne("SELECT status FROM store_order WHERE order_id = ?",
                rs -> rs.getString(1), orderId).orElse("UNKNOWN");
        List<Long> libraryIds = "SUCCESS".equals(p.status())
                ? tx.query("SELECT library_id FROM library_entry WHERE order_id = ? ORDER BY line_no", rs -> rs.getLong(1), orderId)
                : List.of();
        return Optional.of(new PaymentResult(p.paymentId(), orderId, p.status(), orderStatus, p.amount(),
                p.attemptedAt(), requestKey, true, null, libraryIds));
    }

    static Map<Long, ProductRow> loadProducts(Tx tx, List<Long> productIds) throws SQLException {
        return tx.query("""
                SELECT p.product_id, p.title, p.list_price, p.is_active,
                       (bg.product_id IS NOT NULL) AS is_base_game, d.base_game_id
                  FROM product p
                  LEFT JOIN base_game bg ON bg.product_id = p.product_id
                  LEFT JOIN dlc d        ON d.product_id = p.product_id
                 WHERE p.product_id = ANY(?)
                """, rs -> new ProductRow(rs.getLong("product_id"), rs.getString("title"),
                rs.getBigDecimal("list_price"), rs.getBoolean("is_active"), rs.getBoolean("is_base_game"),
                Tx.getLongOrNull(rs, "base_game_id")), productIds)
                .stream().collect(Collectors.toMap(ProductRow::productId, Function.identity()));
    }

    static void assertNotOwned(Tx tx, long userId, List<Long> productIds, Map<Long, ProductRow> products)
            throws SQLException {
        List<Long> owned = tx.query(
                "SELECT product_id FROM library_entry WHERE user_id = ? AND product_id = ANY(?) ORDER BY product_id",
                rs -> rs.getLong(1), userId, productIds);
        if (!owned.isEmpty()) {
            String titles = owned.stream().map(id -> products.get(id).title()).collect(Collectors.joining(", "));
            throw ApiException.conflict("ALREADY_OWNED", "[BR02] Bu ürünlere zaten sahipsiniz: " + titles);
        }
    }

    static void assertDlcPrerequisites(Tx tx, long userId, List<Long> productIds, Map<Long, ProductRow> products)
            throws SQLException {
        Set<Long> inCart = new LinkedHashSet<>(productIds);
        for (Long pid : productIds) {
            ProductRow p = products.get(pid);
            if (p.baseGameId() != null && !inCart.contains(p.baseGameId()) && !ownsProduct(tx, userId, p.baseGameId())) {
                throw ApiException.unprocessable("DLC_REQUIRES_BASE_GAME",
                        "[BR05] '" + p.title() + "' DLC'si için ana oyuna sahip olmalı veya aynı sepette satın almalısınız.");
            }
        }
    }

    static boolean ownsProduct(Tx tx, long userId, long productId) throws SQLException {
        return tx.queryOne("SELECT 1 FROM library_entry WHERE user_id = ? AND product_id = ?",
                rs -> 1, userId, productId).isPresent();
    }
}
