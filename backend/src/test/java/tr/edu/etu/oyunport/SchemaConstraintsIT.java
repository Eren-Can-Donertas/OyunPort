package tr.edu.etu.oyunport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import tr.edu.etu.oyunport.support.Fixtures;
import tr.edu.etu.oyunport.support.IntegrationTestBase;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Veritabanı kısıtları backend'den bağımsız olarak doğrudan SQL ile sınanır
 * (rapor §5; BR01–BR09). Her negatif durumda beklenen SQLSTATE ve kısıt/kural adı kontrol edilir.
 */
class SchemaConstraintsIT extends IntegrationTestBase {

    @FunctionalInterface
    interface SqlBlock {
        void run(Connection c) throws SQLException;
    }

    /** Bloğu tek transaction'da çalıştırıp commit etmeyi dener; beklenen hatayı döndürür. */
    private PSQLException expectFailure(SqlBlock block) {
        try (Connection c = dbAdmin()) {
            c.setAutoCommit(false);
            try {
                block.run(c);
                c.commit();
            } catch (PSQLException e) {
                c.rollback();
                return e;
            }
            fail("Hata bekleniyordu ama transaction commit edildi");
            return null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sql(Connection c, String sql, Object... p) throws SQLException {
        Fixtures.exec(c, sql, p);
    }

    private static long id(Connection c, String sql, Object... p) throws SQLException {
        try (var ps = Fixtures.prepare(c, sql, p); var rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void assertRule(PSQLException e, String state, String ruleOrConstraint) {
        assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(state);
        String constraint = e.getServerErrorMessage() == null ? null : e.getServerErrorMessage().getConstraint();
        assertThat((constraint != null && constraint.equals(ruleOrConstraint)) || e.getMessage().contains(ruleOrConstraint))
                .as("Beklenen kural/kısıt: %s, gelen: %s / %s", ruleOrConstraint, constraint, e.getMessage()).isTrue();
    }

    // ------------------------------------------------------------------ şema

    @Test
    @DisplayName("Rapordaki 18 tablonun tamamı oluşturulmuştur")
    void allReportTablesExist() {
        List<String> tables = fx.column("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                 ORDER BY 1""");
        assertThat(tables).containsExactlyInAnyOrder("app_user", "app_role", "user_role", "publisher", "developer",
                "product", "base_game", "dlc", "genre", "product_genre", "product_developer", "wishlist",
                "store_order", "order_item", "payment_attempt", "library_entry", "play_session", "review");
    }

    @Test
    @DisplayName("§5 FK silme davranışları: tarihsel bağlantılar RESTRICT, bağımlı ilişkiler CASCADE; tüm FK'ler ON UPDATE RESTRICT")
    void foreignKeyActionsMatchReport() {
        List<String> rows = fx.column("""
                SELECT conname || ':' || confdeltype::text || confupdtype::text FROM pg_constraint
                 WHERE contype = 'f' AND connamespace = 'public'::regnamespace ORDER BY conname""");
        Map<String, String> expected = Map.ofEntries(
                Map.entry("fk_user_role_user", "cr"), Map.entry("fk_user_role_role", "rr"),
                Map.entry("fk_wishlist_user", "cr"), Map.entry("fk_wishlist_product", "cr"),
                Map.entry("fk_product_genre_product", "cr"), Map.entry("fk_product_genre_genre", "rr"),
                Map.entry("fk_product_developer_product", "cr"), Map.entry("fk_product_developer_developer", "rr"),
                Map.entry("fk_product_publisher", "rr"), Map.entry("fk_base_game_product", "rr"),
                Map.entry("fk_dlc_product", "rr"), Map.entry("fk_dlc_base_game", "rr"),
                Map.entry("fk_store_order_user", "rr"), Map.entry("fk_order_item_order", "rr"),
                Map.entry("fk_order_item_product", "rr"), Map.entry("fk_payment_attempt_order", "rr"),
                Map.entry("fk_library_entry_user", "rr"), Map.entry("fk_library_entry_product", "rr"),
                Map.entry("fk_library_entry_source_item", "rr"), Map.entry("fk_play_session_library", "rr"),
                Map.entry("fk_review_ownership", "rr"), Map.entry("fk_review_base_game", "rr"));
        assertThat(rows).hasSize(expected.size());
        for (String row : rows) {
            String[] kv = row.split(":");
            assertThat(kv[1]).as(kv[0]).isEqualTo(expected.get(kv[0]));
        }
    }

    // ------------------------------------------------------------------ BR01 + katalog

    @Test
    @DisplayName("BR01 total: alt türü olmayan PRODUCT commit edilemez")
    void productWithoutSubtypeRejected() {
        Fixtures.Catalog cat = fx.catalog();
        PSQLException e = expectFailure(c -> {
            long p = id(c, "INSERT INTO product (publisher_id, title, list_price, release_date) VALUES (?, 'Alt türsüz', 10, current_date) RETURNING product_id", cat.publisherId());
            sql(c, "INSERT INTO product_genre VALUES (?, ?)", p, cat.genreId());
            sql(c, "INSERT INTO product_developer VALUES (?, ?)", p, cat.developerId());
        });
        assertRule(e, "23514", "[BR01]");
    }

    @Test
    @DisplayName("BR01 disjoint: aynı ürün hem BASE_GAME hem DLC olamaz")
    void productInBothSubtypesRejected() {
        Fixtures.Catalog cat = fx.catalog();
        long base = fx.baseGame(cat, "Disjoint Ana", "10");
        PSQLException e = expectFailure(c -> {
            long p = id(c, "INSERT INTO product (publisher_id, title, list_price, release_date) VALUES (?, 'İkili', 10, current_date) RETURNING product_id", cat.publisherId());
            sql(c, "INSERT INTO base_game VALUES (?, 'x')", p);
            sql(c, "INSERT INTO dlc VALUES (?, ?, 'y')", p, base);
        });
        assertRule(e, "23514", "[BR01]");
    }

    @Test
    @DisplayName("§4.2: GENRE veya DEVELOPER bağlantısı olmayan ürün commit edilemez; son genre silinemez")
    void productNeedsGenreAndDeveloper() {
        Fixtures.Catalog cat = fx.catalog();
        PSQLException noGenre = expectFailure(c -> {
            long p = id(c, "INSERT INTO product (publisher_id, title, list_price, release_date) VALUES (?, 'Türsüz', 10, current_date) RETURNING product_id", cat.publisherId());
            sql(c, "INSERT INTO base_game VALUES (?, 'x')", p);
            sql(c, "INSERT INTO product_developer VALUES (?, ?)", p, cat.developerId());
        });
        assertRule(noGenre, "23514", "GENRE");
        long g = fx.baseGame(cat, "Tek Türlü", "10");
        PSQLException lastGenre = expectFailure(c -> sql(c, "DELETE FROM product_genre WHERE product_id = ?", g));
        assertRule(lastGenre, "23514", "GENRE");
    }

    @Test
    @DisplayName("BR05: DLC.base_game_id yalnızca BASE_GAME'e bağlanır (DLC'ye bağlanamaz)")
    void dlcMustReferenceBaseGame() {
        Fixtures.Catalog cat = fx.catalog();
        long base = fx.baseGame(cat, "Ana", "10");
        long dlc = fx.dlc(cat, base, "DLC", "5");
        PSQLException e = expectFailure(c -> {
            long p = id(c, "INSERT INTO product (publisher_id, title, list_price, release_date) VALUES (?, 'DLC of DLC', 1, current_date) RETURNING product_id", cat.publisherId());
            sql(c, "INSERT INTO dlc VALUES (?, ?, 'z')", p, dlc);
        });
        assertRule(e, "23503", "fk_dlc_base_game");
    }

    // ------------------------------------------------------------------ CHECK kısıtları

    @Test
    @DisplayName("Domain CHECK: fiyat/ödeme >= 0, line_no > 0, rating 1–5, ended_at >= started_at, durum değerleri, e-posta biçimi")
    void domainChecks() {
        Fixtures.Catalog cat = fx.catalog();
        long base = fx.baseGame(cat, "Check Oyunu", "10");
        long user = fx.user();
        long order = fx.purchase(user, Instant.now(), base);
        long lib = fx.libraryId(user, base);

        assertRule(expectFailure(c -> sql(c, "UPDATE product SET list_price = -1 WHERE product_id = ?", base)), "23514", "ck_product_list_price");
        assertRule(expectFailure(c -> sql(c, "INSERT INTO app_user (email, display_name, password_hash) VALUES ('BÜYÜK@Harf.com', 'Ad Soyad', '$2b$10$abcdefghijklmnopqrstuv')")), "23514", "ck_app_user_email");
        assertRule(expectFailure(c -> sql(c, "INSERT INTO review (user_id, base_game_id, rating) VALUES (?, ?, 6)", user, base)), "23514", "ck_review_rating");
        assertRule(expectFailure(c -> sql(c, "INSERT INTO review (user_id, base_game_id, rating) VALUES (?, ?, 0)", user, base)), "23514", "ck_review_rating");
        assertRule(expectFailure(c -> sql(c, "INSERT INTO play_session (library_id, started_at, ended_at) VALUES (?, now(), now() - interval '1 hour')", lib)), "23514", "ck_play_session_interval");
        assertRule(expectFailure(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid()) RETURNING order_id", user);
            sql(c, "INSERT INTO order_item VALUES (?, 0, ?, 10)", o, base);
        }), "23514", "ck_order_item_line_no");
        assertRule(expectFailure(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid()) RETURNING order_id", user);
            sql(c, "INSERT INTO order_item VALUES (?, 1, ?, -5)", o, base);
        }), "23514", "ck_order_item_price");
        long pending = fx.pendingOrder(user, Instant.now(), fx.baseGame(cat, "Check Bekleyen", "10"));
        assertRule(expectFailure(c -> sql(c, "UPDATE store_order SET status = 'SHIPPED' WHERE order_id = ?", pending)), "23514", "ck_store_order_status");
        assertRule(expectFailure(c -> sql(c, "INSERT INTO payment_attempt (order_id, amount, status, request_key) VALUES (?, 10, 'PENDING', gen_random_uuid())", pending)), "23514", "ck_payment_attempt_status");
    }

    // ------------------------------------------------------------------ BR03, BR04, BR09

    @Test
    @DisplayName("BR03: siparişte aynı ürün iki kez ve aynı line_no iki kez bulunamaz")
    void orderItemUniqueness() {
        Fixtures.Catalog cat = fx.catalog();
        long g1 = fx.baseGame(cat, "U1", "10");
        long g2 = fx.baseGame(cat, "U2", "10");
        long user = fx.user();
        assertRule(expectFailure(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid()) RETURNING order_id", user);
            sql(c, "INSERT INTO order_item VALUES (?, 1, ?, 10), (?, 2, ?, 10)", o, g1, o, g1);
        }), "23505", "uq_order_item_order_product");
        assertRule(expectFailure(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid()) RETURNING order_id", user);
            sql(c, "INSERT INTO order_item VALUES (?, 1, ?, 10), (?, 1, ?, 10)", o, g1, o, g2);
        }), "23505", "pk_order_item");
    }

    @Test
    @DisplayName("BR04: kalemsiz sipariş commit edilemez")
    void orderWithoutItemsRejected() {
        long user = fx.user();
        assertRule(expectFailure(c -> sql(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid())", user)),
                "23514", "[BR04]");
    }

    @Test
    @DisplayName("BR09: sipariş PENDING oluşturulur; SUCCESS ödeme olmadan COMPLETED yapılamaz; SUCCESS ödeme aynı transaction'da COMPLETED gerektirir")
    void orderStateMachine() {
        Fixtures.Catalog cat = fx.catalog();
        long g = fx.baseGame(cat, "Durum Makinesi", "20");
        long user = fx.user();

        assertRule(expectFailure(c -> sql(c, "INSERT INTO store_order (user_id, status, completed_at, checkout_key) VALUES (?, 'COMPLETED', now(), gen_random_uuid())", user)),
                "23514", "[BR09]");
        assertRule(expectFailure(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid()) RETURNING order_id", user);
            sql(c, "INSERT INTO order_item VALUES (?, 1, ?, 20)", o, g);
            sql(c, "UPDATE store_order SET status = 'COMPLETED', completed_at = now() WHERE order_id = ?", o);
        }), "23514", "[BR09]");
        assertRule(expectFailure(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid()) RETURNING order_id", user);
            sql(c, "INSERT INTO order_item VALUES (?, 1, ?, 20)", o, g);
            sql(c, "INSERT INTO payment_attempt (order_id, amount, status, request_key) VALUES (?, 20, 'SUCCESS', gen_random_uuid())", o);
        }), "23514", "[BR09]");
    }

    @Test
    @DisplayName("BR09: sipariş başına en fazla bir SUCCESS ödeme (kısmi UNIQUE indeks)")
    void atMostOneSuccessfulPayment() {
        Fixtures.Catalog cat = fx.catalog();
        long g = fx.baseGame(cat, "Tek Başarı", "20");
        long user = fx.user();
        assertRule(expectFailure(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid()) RETURNING order_id", user);
            sql(c, "INSERT INTO order_item VALUES (?, 1, ?, 20)", o, g);
            sql(c, "INSERT INTO payment_attempt (order_id, amount, status, request_key) VALUES (?, 20, 'SUCCESS', gen_random_uuid())", o);
            sql(c, "INSERT INTO payment_attempt (order_id, amount, status, request_key) VALUES (?, 20, 'SUCCESS', gen_random_uuid())", o);
        }), "23505", "uq_one_successful_payment");
    }

    @Test
    @DisplayName("BR08: tamamlanmış sipariş yeniden açılamaz, kalem eklenemez; kalemler (BR07) ve ödeme geçmişi değiştirilemez")
    void completedOrderIsImmutable() {
        Fixtures.Catalog cat = fx.catalog();
        long g = fx.baseGame(cat, "Değişmez", "20");
        long g2 = fx.baseGame(cat, "Değişmez 2", "20");
        long user = fx.user();
        long order = fx.purchase(user, Instant.now(), g);

        assertRule(expectFailure(c -> sql(c, "UPDATE store_order SET status = 'PENDING', completed_at = NULL WHERE order_id = ?", order)), "23514", "[BR08]");
        assertRule(expectFailure(c -> sql(c, "UPDATE store_order SET status = 'CANCELLED', completed_at = NULL WHERE order_id = ?", order)), "23514", "[BR08]");
        assertRule(expectFailure(c -> sql(c, "INSERT INTO order_item VALUES (?, 2, ?, 20)", order, g2)), "23514", "[BR08]");
        assertRule(expectFailure(c -> sql(c, "UPDATE order_item SET unit_price_paid = 1 WHERE order_id = ?", order)), "23514", "[BR07]");
        assertRule(expectFailure(c -> sql(c, "UPDATE payment_attempt SET status = 'FAILED' WHERE order_id = ?", order)), "23514", "[BR09]");
        assertRule(expectFailure(c -> sql(c, "DELETE FROM payment_attempt WHERE order_id = ?", order)), "23514", "[BR09]");
        assertRule(expectFailure(c -> sql(c, "DELETE FROM library_entry WHERE order_id = ?", order)), "23514", "[BR02]");
    }

    // ------------------------------------------------------------------ LIBRARY_ENTRY kaynağı (BR04, BR05)

    @Test
    @DisplayName("BR04: kütüphane kaydı yalnızca kendi, COMPLETED + SUCCESS ödemeli sipariş kaleminden ve aynı üründen oluşur")
    void libraryEntrySourceConsistency() {
        Fixtures.Catalog cat = fx.catalog();
        long g = fx.baseGame(cat, "Kaynak", "20");
        long g2 = fx.baseGame(cat, "Kaynak 2", "20");
        long owner = fx.user();
        long other = fx.user();

        // Başka kullanıcı, sahibin siparişinin kalemini kaynak gösteremez (deferred trigger, commit anında)
        assertRule(expectFailure(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid()) RETURNING order_id", owner);
            sql(c, "INSERT INTO order_item VALUES (?, 1, ?, 20)", o, g2);
            sql(c, "INSERT INTO payment_attempt (order_id, amount, status, request_key) VALUES (?, 20, 'SUCCESS', gen_random_uuid())", o);
            sql(c, "INSERT INTO library_entry (user_id, product_id, order_id, line_no) VALUES (?, ?, ?, 1)", other, g2, o);
            sql(c, "UPDATE store_order SET status = 'COMPLETED', completed_at = now() WHERE order_id = ?", o);
        }), "23514", "[BR04]");

        // Aynı sipariş kalemi ikinci bir sahipliğin kaynağı olamaz (UQ (order_id, line_no))
        long done = fx.purchase(owner, Instant.now(), g2);
        assertRule(expectFailure(c -> sql(c, "INSERT INTO library_entry (user_id, product_id, order_id, line_no) VALUES (?, ?, ?, 1)", other, g2, done)),
                "23505", "uq_library_entry_source_item");

        // PENDING siparişten edinim olmaz
        long pending = fx.inTx(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid()) RETURNING order_id", owner);
            sql(c, "INSERT INTO order_item VALUES (?, 1, ?, 20)", o, g);
            return o;
        });
        assertRule(expectFailure(c -> sql(c, "INSERT INTO library_entry (user_id, product_id, order_id, line_no) VALUES (?, ?, ?, 1)", owner, g, pending)),
                "23514", "[BR04]");

        // Kaynak kalemin ürünü farklıysa reddedilir
        assertRule(expectFailure(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid()) RETURNING order_id", other);
            sql(c, "INSERT INTO order_item VALUES (?, 1, ?, 20)", o, g);
            sql(c, "INSERT INTO payment_attempt (order_id, amount, status, request_key) VALUES (?, 20, 'SUCCESS', gen_random_uuid())", o);
            sql(c, "INSERT INTO library_entry (user_id, product_id, order_id, line_no) VALUES (?, ?, ?, 1)", other, g2, o);
            sql(c, "UPDATE store_order SET status = 'COMPLETED', completed_at = now() WHERE order_id = ?", o);
        }), "23514", "[BR04]");
    }

    @Test
    @DisplayName("BR05 (veritabanı düzeyi): servis atlansa bile ana oyunu olmayan kullanıcıya DLC sahipliği commit edilemez")
    void dlcOwnershipRequiresBaseGameAtDbLevel() {
        Fixtures.Catalog cat = fx.catalog();
        long base = fx.baseGame(cat, "Ana", "50");
        long dlc = fx.dlc(cat, base, "DLC", "10");
        long user = fx.user();
        PSQLException e = expectFailure(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid()) RETURNING order_id", user);
            sql(c, "INSERT INTO order_item VALUES (?, 1, ?, 10)", o, dlc);
            sql(c, "INSERT INTO payment_attempt (order_id, amount, status, request_key) VALUES (?, 10, 'SUCCESS', gen_random_uuid())", o);
            sql(c, "INSERT INTO library_entry (user_id, product_id, order_id, line_no) VALUES (?, ?, ?, 1)", user, dlc, o);
            sql(c, "UPDATE store_order SET status = 'COMPLETED', completed_at = now() WHERE order_id = ?", o);
        });
        assertRule(e, "23514", "[BR05]");
        assertThat(fx.scalarLong("SELECT count(*) FROM library_entry WHERE user_id = ?", user)).isZero();
    }

    // ------------------------------------------------------------------ BR06

    @Test
    @DisplayName("BR06: oturum yalnızca BASE_GAME sahipliğine, edinimden sonra; aynı anda tek açık oturum")
    void playSessionRules() {
        Fixtures.Catalog cat = fx.catalog();
        long base = fx.baseGame(cat, "Oturum Ana", "50");
        long dlc = fx.dlc(cat, base, "Oturum DLC", "10");
        long user = fx.user();
        Instant acquired = Instant.now().minus(2, ChronoUnit.DAYS);
        fx.purchase(user, acquired, base, dlc);
        long baseLib = fx.libraryId(user, base);
        long dlcLib = fx.libraryId(user, dlc);

        assertRule(expectFailure(c -> sql(c, "INSERT INTO play_session (library_id, started_at) VALUES (?, now())", dlcLib)), "23514", "[BR06]");
        assertRule(expectFailure(c -> sql(c, "INSERT INTO play_session (library_id, started_at, ended_at) VALUES (?, now() - interval '10 days', now() - interval '9 days')", baseLib)), "23514", "[BR06]");
        assertRule(expectFailure(c -> {
            sql(c, "INSERT INTO play_session (library_id, started_at) VALUES (?, now())", baseLib);
            sql(c, "INSERT INTO play_session (library_id, started_at) VALUES (?, now())", baseLib);
        }), "23505", "uq_play_session_one_open");
    }

    @Test
    @DisplayName("BR06: yorum için sahiplik (FK -> LIBRARY_ENTRY) ve BASE_GAME (FK) zorunlu; kullanıcı-oyun başına tek yorum (PK)")
    void reviewRules() {
        Fixtures.Catalog cat = fx.catalog();
        long base = fx.baseGame(cat, "Yorum Ana", "50");
        long dlc = fx.dlc(cat, base, "Yorum DLC", "10");
        long notOwned = fx.baseGame(cat, "Sahip Olunmayan", "50");
        long user = fx.user();
        fx.purchase(user, Instant.now(), base, dlc);

        assertRule(expectFailure(c -> sql(c, "INSERT INTO review (user_id, base_game_id, rating) VALUES (?, ?, 4)", user, notOwned)), "23503", "fk_review_ownership");
        assertRule(expectFailure(c -> sql(c, "INSERT INTO review (user_id, base_game_id, rating) VALUES (?, ?, 4)", user, dlc)), "23503", "fk_review_base_game");
        fx.sql("INSERT INTO review (user_id, base_game_id, rating) VALUES (?, ?, 4)", user, base);
        assertRule(expectFailure(c -> sql(c, "INSERT INTO review (user_id, base_game_id, rating) VALUES (?, ?, 5)", user, base)), "23505", "pk_review");
    }

    @Test
    @DisplayName("BR08: tarihsel kaydı olan kullanıcı ve ürün silinemez (ON DELETE RESTRICT)")
    void historicalRowsCannotBeDeleted() {
        Fixtures.Catalog cat = fx.catalog();
        long g = fx.baseGame(cat, "Silinemez", "50");
        long user = fx.user();
        fx.purchase(user, Instant.now(), g);
        assertRule(expectFailure(c -> sql(c, "DELETE FROM app_user WHERE user_id = ?", user)), "23503", "fk_");
        assertRule(expectFailure(c -> sql(c, "DELETE FROM product WHERE product_id = ?", g)), "23503", "fk_");
        assertRule(expectFailure(c -> sql(c, "DELETE FROM genre WHERE genre_id = ?", cat.genreId())), "23503", "fk_product_genre_genre");
    }

    @Test
    @DisplayName("Idempotency anahtarları veritabanında UNIQUE: checkout_key ve request_key")
    void idempotencyKeysUnique() {
        Fixtures.Catalog cat = fx.catalog();
        long g = fx.baseGame(cat, "Anahtar", "50");
        long user = fx.user();
        UUID key = UUID.randomUUID();
        fx.inTx(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, ?) RETURNING order_id", user, key);
            sql(c, "INSERT INTO order_item VALUES (?, 1, ?, 50)", o, g);
            return o;
        });
        assertRule(expectFailure(c -> {
            long o = id(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, ?) RETURNING order_id", user, key);
            sql(c, "INSERT INTO order_item VALUES (?, 1, ?, 50)", o, g);
        }), "23505", "uq_store_order_checkout_key");
    }
}
