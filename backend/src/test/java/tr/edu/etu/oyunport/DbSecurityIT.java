package tr.edu.etu.oyunport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tr.edu.etu.oyunport.support.Api;
import tr.edu.etu.oyunport.support.Fixtures;
import tr.edu.etu.oyunport.support.IntegrationTestBase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Veritabanı rolleri, yetkiler ve satır güvenliği (FR12, rapor §7.2, §8.4).
 * Her test ilgili PostgreSQL rolüyle doğrudan bağlanır; uygulama katmanı devre dışıdır.
 */
class DbSecurityIT extends IntegrationTestBase {

    private static long scalar(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = Fixtures.prepare(c, sql, p); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static int update(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = Fixtures.prepare(c, sql, p)) {
            return ps.executeUpdate();
        }
    }

    private static void asUser(Connection c, long userId) throws SQLException {
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.user_id', ?, true)")) {
            ps.setString(1, Long.toString(userId));
            ps.execute();
        }
    }

    private static void denied(Connection c, String sql) {
        assertThatThrownBy(() -> {
            try (var st = c.createStatement()) {
                st.execute(sql);
            }
        }).as(sql).isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
    }

    @Test
    @DisplayName("Roller: hiçbiri süper kullanıcı değil, BYPASSRLS yok; db_admin yalnızca CREATEROLE")
    void roleAttributes() {
        List<String> roles = fx.column("""
                SELECT rolname || ':' || rolsuper || ':' || rolbypassrls || ':' || rolcreaterole || ':' || rolcanlogin
                  FROM pg_roles WHERE rolname IN ('db_admin','app_customer','app_admin','reporting_readonly','data_loader')
                 ORDER BY rolname""");
        assertThat(roles).containsExactly(
                "app_admin:false:false:false:true",
                "app_customer:false:false:false:true",
                "data_loader:false:false:false:true",
                "db_admin:false:false:true:true",
                "reporting_readonly:false:false:false:true");
    }

    @Test
    @DisplayName("PUBLIC: veritabanı CONNECT, şema USAGE, tablo ve fonksiyon yetkisi yok")
    void publicHasNoPrivileges() {
        assertThat(fx.scalarString("SELECT has_database_privilege('public', current_database(), 'CONNECT')")).isEqualTo("f");
        assertThat(fx.scalarString("SELECT has_schema_privilege('public', 'public', 'USAGE')")).isEqualTo("f");
        assertThat(fx.scalarLong("SELECT count(*) FROM information_schema.role_table_grants WHERE grantee = 'PUBLIC' AND table_schema = 'public'")).isZero();
        assertThat(fx.scalarString("SELECT has_function_privilege('public', 'api_login_lookup(text)', 'EXECUTE')")).isEqualTo("f");
    }

    @Test
    @DisplayName("§8.4 RLS: app_customer başka kullanıcının sipariş, ödeme, kütüphane ve istek listesini göremez/değiştiremez")
    void customerRowLevelSecurity() throws SQLException {
        Fixtures.Catalog cat = fx.catalog();
        long g = fx.baseGame(cat, "RLS", "40");
        long alice = fx.user();
        long bob = fx.user();
        long aliceOrder = fx.purchase(alice, Instant.now(), g);
        long bobOrder = fx.purchase(bob, Instant.now(), g);
        fx.sql("INSERT INTO wishlist (user_id, product_id) VALUES (?, ?)", bob, g);

        try (Connection c = connect("app_customer", CUSTOMER_PW)) {
            asUser(c, alice);
            assertThat(scalar(c, "SELECT count(*) FROM store_order WHERE order_id = ?", aliceOrder)).isEqualTo(1);
            assertThat(scalar(c, "SELECT count(*) FROM store_order WHERE order_id = ?", bobOrder)).isZero();
            assertThat(scalar(c, "SELECT count(*) FROM store_order WHERE user_id = ?", bob)).isZero();
            assertThat(scalar(c, "SELECT count(*) FROM order_item WHERE order_id = ?", bobOrder)).isZero();
            assertThat(scalar(c, "SELECT count(*) FROM payment_attempt WHERE order_id = ?", bobOrder)).isZero();
            assertThat(scalar(c, "SELECT count(*) FROM library_entry WHERE user_id = ?", bob)).isZero();
            assertThat(scalar(c, "SELECT count(*) FROM wishlist WHERE user_id = ?", bob)).isZero();
            assertThat(scalar(c, "SELECT count(*) FROM app_user")).isEqualTo(1);           // yalnızca kendisi
            assertThat(scalar(c, "SELECT count(*) FROM v_order_summary")).isEqualTo(1);    // security_invoker view
            assertThat(update(c, "UPDATE store_order SET status = 'CANCELLED' WHERE order_id = ?", bobOrder)).isZero();
            assertThat(update(c, "UPDATE app_user SET display_name = 'Hacked' WHERE user_id = ?", bob)).isZero();
            c.rollback();
        }
        assertThat(fx.scalarString("SELECT display_name FROM app_user WHERE user_id = ?", bob)).isNotEqualTo("Hacked");
    }

    @Test
    @DisplayName("RLS: oturum kimliği ayarlanmamış bağlantı hiçbir müşteri satırını göremez")
    void noSessionIdentityNoRows() throws SQLException {
        Fixtures.Catalog cat = fx.catalog();
        long g = fx.baseGame(cat, "Kimliksiz", "40");
        fx.purchase(fx.user(), Instant.now(), g);
        try (Connection c = connect("app_customer", CUSTOMER_PW)) {
            assertThat(scalar(c, "SELECT count(*) FROM store_order")).isZero();
            assertThat(scalar(c, "SELECT count(*) FROM library_entry")).isZero();
            assertThat(scalar(c, "SELECT count(*) FROM app_user")).isZero();
            assertThat(scalar(c, "SELECT count(*) FROM product")).isPositive();   // katalog herkese açık
        }
    }

    @Test
    @DisplayName("RLS WITH CHECK: müşteri başka kullanıcı adına sipariş veya istek listesi satırı ekleyemez")
    void customerCannotWriteForOthers() throws SQLException {
        Fixtures.Catalog cat = fx.catalog();
        long g = fx.baseGame(cat, "Başkası Adına", "40");
        long alice = fx.user();
        long bob = fx.user();
        try (Connection c = connect("app_customer", CUSTOMER_PW)) {
            asUser(c, alice);
            assertThatThrownBy(() -> update(c, "INSERT INTO store_order (user_id, checkout_key) VALUES (?, gen_random_uuid())", bob))
                    .isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
            c.rollback();
            asUser(c, alice);
            assertThatThrownBy(() -> update(c, "INSERT INTO wishlist (user_id, product_id) VALUES (?, ?)", bob, g))
                    .isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
            c.rollback();
        }
    }

    @Test
    @DisplayName("§8.4 app_customer yönetim tablolarına ve rapor view'larına erişemez, katalog DML yapamaz, sahipliği silemez")
    void customerCannotTouchAdminObjects() throws SQLException {
        try (Connection c = connect("app_customer", CUSTOMER_PW)) {
            denied(c, "INSERT INTO genre (name) VALUES ('hack')");
            denied(c, "UPDATE product SET list_price = 0");
            denied(c, "DELETE FROM product");
            denied(c, "SELECT * FROM user_role");
            denied(c, "SELECT * FROM rpt_overview");
            denied(c, "SELECT * FROM rpt_q3_above_average_customers");
            denied(c, "DELETE FROM library_entry");
            denied(c, "UPDATE app_user SET is_active = true");
            denied(c, "UPDATE app_user SET email = 'x@y.z'");
            denied(c, "DELETE FROM payment_attempt");
        }
    }

    @Test
    @DisplayName("app_admin: katalog DML ve rapor view'ları var; sipariş/kullanıcı tablolarına doğrudan erişim ve ürün silme yok")
    void adminRolePrivileges() throws SQLException {
        try (Connection c = connect("app_admin", ADMIN_PW)) {
            c.setAutoCommit(false);
            assertThat(scalar(c, "SELECT count(*) FROM rpt_overview")).isEqualTo(1);
            update(c, "INSERT INTO genre (name) VALUES ('Admin Test " + uniq() + "')");
            c.rollback();
            denied(c, "SELECT * FROM store_order");
            c.rollback();
            denied(c, "SELECT * FROM app_user");
            c.rollback();
            denied(c, "SELECT * FROM payment_attempt");
            c.rollback();
            denied(c, "DELETE FROM product");
            c.rollback();
            denied(c, "INSERT INTO library_entry (user_id, product_id, order_id, line_no) VALUES (1, 1, 1, 1)");
            c.rollback();
        }
    }

    @Test
    @DisplayName("reporting_readonly: yalnızca analitik view'lar ve katalog SELECT; INSERT/UPDATE/DELETE ve kullanıcı/sipariş tabloları yok")
    void reportingReadonlyPrivileges() throws SQLException {
        try (Connection c = connect("reporting_readonly", REPORTING_PW)) {
            assertThat(scalar(c, "SELECT count(*) FROM rpt_overview")).isEqualTo(1);
            scalar(c, "SELECT count(*) FROM rpt_q1_monthly_top_publishers");
            scalar(c, "SELECT count(*) FROM v_game_sales_monthly");
            denied(c, "INSERT INTO genre (name) VALUES ('ro')");
            denied(c, "UPDATE product SET title = 'x'");
            denied(c, "DELETE FROM genre");
            denied(c, "SELECT * FROM app_user");
            denied(c, "SELECT * FROM store_order");
            denied(c, "SELECT * FROM library_entry");
            denied(c, "SELECT api_login_lookup('admin@oyunport.local')");
        }
    }

    @Test
    @DisplayName("data_loader: yükleme dışında kalıcı INSERT yetkisi yok (yükleme betiği verir ve geri alır)")
    void dataLoaderHasNoStandingPrivileges() throws SQLException {
        try (Connection c = connect("data_loader", LOADER_PW)) {
            denied(c, "INSERT INTO app_user (email, display_name, password_hash) VALUES ('a@b.cd', 'Ab', '$2b$10$abcdefghijklmnopqrstuv')");
            denied(c, "SELECT * FROM store_order");
            denied(c, "INSERT INTO product (publisher_id, title, list_price, release_date) VALUES (1, 'x', 1, current_date)");
        }
    }

    @Test
    @DisplayName("SQL injection: arama metni bind parametresi olarak gider; zararlı girdi sorguyu değiştirmez")
    void sqlInjectionAttemptIsHarmless() {
        Fixtures.Catalog cat = fx.catalog();
        fx.baseGame(cat, "Güvenli Oyun", "10");
        long before = fx.scalarLong("SELECT count(*) FROM product");
        String evil = java.net.URLEncoder.encode("' OR 1=1; DROP TABLE product; --", java.nio.charset.StandardCharsets.UTF_8);
        Api.Resp r = api.get("/api/catalog/products?q=" + evil, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("total").asLong()).isZero();
        Api.Resp sort = api.get("/api/catalog/products?sort=" + java.net.URLEncoder.encode("title; DROP TABLE product", java.nio.charset.StandardCharsets.UTF_8), null);
        assertThat(sort.status()).isEqualTo(400);
        assertThat(fx.scalarLong("SELECT count(*) FROM product")).isEqualTo(before);
    }

    @Test
    @DisplayName("Hassas veri: parola hash'i API yanıtlarında, e-posta herkese açık yorum view'ında yer almaz")
    void sensitiveDataNotExposed() {
        Api.Session s = newCustomer();
        Api.Resp me = api.get("/api/me", s);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().toString()).doesNotContain("password").doesNotContain("$2");
        assertThat(fx.column("SELECT column_name FROM information_schema.columns WHERE table_name = 'v_public_review'"))
                .doesNotContain("email", "user_id", "password_hash");
        assertThat(fx.scalarString("SELECT password_hash FROM app_user WHERE user_id = ?", s.userId())).startsWith("$2");
    }
}
