package tr.edu.etu.oyunport.support;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test verisi; şema sahibi db_admin ile doğrudan JDBC. Tüm satırlar migration'daki
 * trigger ve kısıtlardan geçer (devre dışı bırakılan kural yoktur).
 */
public final class Fixtures {

    public record Catalog(long publisherId, long developerId, long genreId) {}

    public Catalog catalog() {
        String u = IntegrationTestBase.uniq();
        return new Catalog(
                insertId("INSERT INTO publisher (name, country) VALUES (?, 'TR') RETURNING publisher_id", "Yayıncı " + u),
                insertId("INSERT INTO developer (name, country) VALUES (?, 'TR') RETURNING developer_id", "Stüdyo " + u),
                insertId("INSERT INTO genre (name) VALUES (?) RETURNING genre_id", "Tür " + u));
    }

    public long baseGame(Catalog c, String title, String price) {
        return inTx(conn -> {
            long id = id(conn, "INSERT INTO product (publisher_id, title, description, list_price, release_date) "
                    + "VALUES (?, ?, 'Test açıklaması', ?, ?) RETURNING product_id",
                    c.publisherId(), title + " " + IntegrationTestBase.uniq(), new BigDecimal(price), LocalDate.of(2023, 1, 15));
            exec(conn, "INSERT INTO base_game (product_id, minimum_requirements) VALUES (?, '8 GB RAM, GTX 1060')", id);
            exec(conn, "INSERT INTO product_genre (product_id, genre_id) VALUES (?, ?)", id, c.genreId());
            exec(conn, "INSERT INTO product_developer (product_id, developer_id) VALUES (?, ?)", id, c.developerId());
            return id;
        });
    }

    public long dlc(Catalog c, long baseGameId, String title, String price) {
        return inTx(conn -> {
            long id = id(conn, "INSERT INTO product (publisher_id, title, description, list_price, release_date) "
                    + "VALUES (?, ?, 'DLC açıklaması', ?, ?) RETURNING product_id",
                    c.publisherId(), title + " " + IntegrationTestBase.uniq(), new BigDecimal(price), LocalDate.of(2023, 6, 1));
            exec(conn, "INSERT INTO dlc (product_id, base_game_id, content_summary) VALUES (?, ?, 'Yeni bölümler')", id, baseGameId);
            exec(conn, "INSERT INTO product_genre (product_id, genre_id) VALUES (?, ?)", id, c.genreId());
            exec(conn, "INSERT INTO product_developer (product_id, developer_id) VALUES (?, ?)", id, c.developerId());
            return id;
        });
    }

    /** API kullanmadan müşteri (rapor testlerinde toplu kullanıcı için). */
    public long user() {
        return inTx(conn -> {
            long id = id(conn, "INSERT INTO app_user (email, display_name, password_hash) VALUES (?, 'Toplu Kullanıcı', "
                    + "'$2b$10$miZdSoTDXbEyR/a/9A2.oONTFqoy8fB5T/7cqHHR8UIVgHyqTwmNK') RETURNING user_id",
                    "bulk-" + IntegrationTestBase.uniq() + "@test.oyunport");
            exec(conn, "INSERT INTO user_role (user_id, role_id) SELECT ?, role_id FROM app_role WHERE role_name = 'CUSTOMER'", id);
            return id;
        });
    }

    /**
     * Tamamlanmış satın almayı doğrudan SQL ile (T2 ile aynı sırayla) oluşturur:
     * PENDING sipariş + kalemler, SUCCESS ödeme, kütüphane kayıtları, COMPLETED.
     */
    public long purchase(long userId, Instant at, long... productIds) {
        return inTx(conn -> {
            Timestamp ts = Timestamp.from(at);
            long orderId = id(conn, "INSERT INTO store_order (user_id, status, created_at, checkout_key) "
                    + "VALUES (?, 'PENDING', ?, ?) RETURNING order_id", userId, ts, UUID.randomUUID());
            BigDecimal total = BigDecimal.ZERO;
            int line = 1;
            for (long pid : productIds) {
                BigDecimal price = scalarDecimal(conn, "SELECT list_price FROM product WHERE product_id = ?", pid);
                exec(conn, "INSERT INTO order_item (order_id, line_no, product_id, unit_price_paid) VALUES (?, ?, ?, ?)",
                        orderId, line++, pid, price);
                total = total.add(price);
            }
            exec(conn, "INSERT INTO payment_attempt (order_id, amount, status, attempted_at, request_key) "
                    + "VALUES (?, ?, 'SUCCESS', ?, ?)", orderId, total, ts, UUID.randomUUID());
            line = 1;
            for (long pid : productIds) {
                exec(conn, "INSERT INTO library_entry (user_id, product_id, order_id, line_no, acquired_at) VALUES (?, ?, ?, ?, ?)",
                        userId, pid, orderId, line++, ts);
            }
            exec(conn, "UPDATE store_order SET status = 'COMPLETED', completed_at = ? WHERE order_id = ?", ts, orderId);
            return orderId;
        });
    }

    /** Ödenmemiş (PENDING) sipariş; fiyatlar güncel list_price'tan. */
    public long pendingOrder(long userId, Instant at, long... productIds) {
        return inTx(conn -> {
            long orderId = id(conn, "INSERT INTO store_order (user_id, status, created_at, checkout_key) "
                    + "VALUES (?, 'PENDING', ?, ?) RETURNING order_id", userId, Timestamp.from(at), UUID.randomUUID());
            int line = 1;
            for (long pid : productIds) {
                exec(conn, "INSERT INTO order_item (order_id, line_no, product_id, unit_price_paid) "
                        + "SELECT ?, ?, product_id, list_price FROM product WHERE product_id = ?", orderId, line++, pid);
            }
            return orderId;
        });
    }

    public long libraryId(long userId, long productId) {
        return scalarLong("SELECT library_id FROM library_entry WHERE user_id = ? AND product_id = ?", userId, productId);
    }

    public void session(long libraryId, Instant start, long minutes) {
        inTx(conn -> {
            exec(conn, "INSERT INTO play_session (library_id, started_at, ended_at) VALUES (?, ?, ?)",
                    libraryId, Timestamp.from(start), Timestamp.from(start.plusSeconds(minutes * 60)));
            return 0L;
        });
    }

    // ------------------------------------------------------------------ genel SQL yardımcıları (db_admin)

    public long scalarLong(String sql, Object... params) {
        return inTx(conn -> {
            try (PreparedStatement ps = prepare(conn, sql, params); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        });
    }

    public String scalarString(String sql, Object... params) {
        return inTxObj(conn -> {
            try (PreparedStatement ps = prepare(conn, sql, params); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    public List<String> column(String sql, Object... params) {
        return inTxObj(conn -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = prepare(conn, sql, params); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        });
    }

    public void sql(String sql, Object... params) {
        inTx(conn -> {
            exec(conn, sql, params);
            return 0L;
        });
    }

    @FunctionalInterface
    public interface SqlFn<T> {
        T apply(Connection c) throws SQLException;
    }

    public long inTx(SqlFn<Long> fn) {
        return inTxObj(fn);
    }

    public <T> T inTxObj(SqlFn<T> fn) {
        try (Connection c = IntegrationTestBase.dbAdmin()) {
            c.setAutoCommit(false);
            try {
                T r = fn.apply(c);
                c.commit();
                return r;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long insertId(String sql, Object... params) {
        return inTx(conn -> id(conn, sql, params));
    }

    static long id(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static BigDecimal scalarDecimal(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    public static void exec(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params)) {
            ps.execute();
        }
    }

    public static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps;
    }
}
