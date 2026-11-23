package tr.edu.etu.oyunport.admin;

import org.springframework.stereotype.Service;
import tr.edu.etu.oyunport.common.ApiException;
import tr.edu.etu.oyunport.common.Page;
import tr.edu.etu.oyunport.db.Db;
import tr.edu.etu.oyunport.db.Tx;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Admin katalog yönetimi (FR10) — app_admin havuzu. Ürün, alt tür (BASE_GAME/DLC),
 * PRODUCT_GENRE ve PRODUCT_DEVELOPER aynı transaction'da kurulur (rapor §4.2); total+disjoint
 * specialization ve en az bir genre/developer kuralları commit anında trigger'larla da denetlenir.
 * Ürünler silinmez, pasife alınır (BR08).
 */
@Service
public class AdminCatalogService {

    private final Db db;

    public AdminCatalogService(Db db) {
        this.db = db;
    }

    public record ProductInput(String type, String title, String description, BigDecimal listPrice,
                               LocalDate releaseDate, Long publisherId, List<Long> developerIds, List<Long> genreIds,
                               String minimumRequirements, Long baseGameId, String contentSummary, Boolean active) {}

    public record AdminProduct(long productId, String type, String title, String description, BigDecimal listPrice,
                               LocalDate releaseDate, boolean active, long publisherId, String publisherName,
                               List<Long> developerIds, List<Long> genreIds, String minimumRequirements,
                               Long baseGameId, String contentSummary, long unitsSold) {}

    public record Ref(long id, String name, String country, long productCount) {}

    // ------------------------------------------------------------------ ürünler

    public Page<AdminProduct> list(long adminId, String q, String type, int page, int size) {
        if (type != null && !type.isBlank() && !"BASE_GAME".equals(type) && !"DLC".equals(type)) {
            throw ApiException.badRequest("type yalnızca BASE_GAME veya DLC olabilir.");
        }
        String t = type == null || type.isBlank() ? null : type;
        int s = Page.clampSize(size);
        int p = Math.max(0, page);
        String like = q == null || q.isBlank() ? null : "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return db.adminReadOnly(adminId, tx -> {
            String filter = """
                     WHERE (?::text IS NULL OR p.title ILIKE ?)
                       AND (?::text IS NULL
                            OR (? = 'BASE_GAME' AND EXISTS (SELECT 1 FROM base_game b WHERE b.product_id = p.product_id))
                            OR (? = 'DLC' AND EXISTS (SELECT 1 FROM dlc d WHERE d.product_id = p.product_id)))
                    """;
            List<Long> ids = tx.query("SELECT p.product_id FROM product p" + filter + " ORDER BY p.product_id DESC LIMIT ? OFFSET ?",
                    rs -> rs.getLong(1), like, like, t, t, t, s, p * s);
            long total = tx.queryLong("SELECT count(*) FROM product p" + filter, like, like, t, t, t);
            List<AdminProduct> items = new java.util.ArrayList<>();
            for (Long id : ids) {
                items.add(load(tx, id));
            }
            return new Page<>(items, p, s, total);
        });
    }

    public AdminProduct get(long adminId, long productId) {
        return db.adminReadOnly(adminId, tx -> load(tx, productId));
    }

    public AdminProduct create(long adminId, ProductInput in) {
        validate(in, true);
        return db.admin(adminId, tx -> {
            long id = tx.insertReturningLong("""
                    INSERT INTO product (publisher_id, title, description, list_price, release_date, is_active)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING product_id
                    """, in.publisherId(), in.title().trim(), blankToNull(in.description()), in.listPrice(),
                    in.releaseDate(), in.active() == null || in.active());
            if ("BASE_GAME".equals(in.type())) {
                tx.update("INSERT INTO base_game (product_id, minimum_requirements) VALUES (?, ?)",
                        id, in.minimumRequirements().trim());
            } else {
                tx.update("INSERT INTO dlc (product_id, base_game_id, content_summary) VALUES (?, ?, ?)",
                        id, in.baseGameId(), in.contentSummary().trim());
            }
            replaceLinks(tx, id, in.genreIds(), in.developerIds());
            return load(tx, id);
        });
    }

    public AdminProduct update(long adminId, long productId, ProductInput in) {
        validate(in, false);
        return db.admin(adminId, tx -> {
            AdminProduct current = load(tx, productId);
            if (in.type() != null && !in.type().equals(current.type())) {
                throw ApiException.unprocessable("SUBTYPE_IMMUTABLE",
                        "[BR01] Ürünün alt türü (BASE_GAME/DLC) sonradan değiştirilemez.");
            }
            tx.update("""
                    UPDATE product SET publisher_id = ?, title = ?, description = ?, list_price = ?,
                                       release_date = ?, is_active = ?
                     WHERE product_id = ?
                    """, in.publisherId(), in.title().trim(), blankToNull(in.description()), in.listPrice(),
                    in.releaseDate(), in.active() == null ? current.active() : in.active(), productId);
            if ("BASE_GAME".equals(current.type())) {
                if (in.minimumRequirements() != null) {
                    tx.update("UPDATE base_game SET minimum_requirements = ? WHERE product_id = ?",
                            in.minimumRequirements().trim(), productId);
                }
            } else if (in.contentSummary() != null) {
                tx.update("UPDATE dlc SET content_summary = ? WHERE product_id = ?", in.contentSummary().trim(), productId);
            }
            replaceLinks(tx, productId, in.genreIds(), in.developerIds());
            return load(tx, productId);
        });
    }

    public AdminProduct setActive(long adminId, long productId, boolean active) {
        return db.admin(adminId, tx -> {
            int n = tx.update("UPDATE product SET is_active = ? WHERE product_id = ?", active, productId);
            if (n == 0) {
                throw ApiException.notFound("Ürün bulunamadı.");
            }
            return load(tx, productId);
        });
    }

    private static void replaceLinks(Tx tx, long productId, List<Long> genreIds, List<Long> developerIds) throws SQLException {
        List<Long> genres = List.copyOf(new LinkedHashSet<>(genreIds));
        List<Long> devs = List.copyOf(new LinkedHashSet<>(developerIds));
        tx.update("DELETE FROM product_genre WHERE product_id = ? AND NOT (genre_id = ANY(?))", productId, genres);
        tx.update("""
                INSERT INTO product_genre (product_id, genre_id)
                SELECT ?, g FROM unnest(?::bigint[]) AS g ON CONFLICT DO NOTHING
                """, productId, genres);
        tx.update("DELETE FROM product_developer WHERE product_id = ? AND NOT (developer_id = ANY(?))", productId, devs);
        tx.update("""
                INSERT INTO product_developer (product_id, developer_id)
                SELECT ?, d FROM unnest(?::bigint[]) AS d ON CONFLICT DO NOTHING
                """, productId, devs);
    }

    private static void validate(ProductInput in, boolean creating) {
        if (in.title() == null || in.title().isBlank()) throw ApiException.badRequest("Başlık zorunludur.");
        if (in.listPrice() == null || in.listPrice().signum() < 0) throw ApiException.badRequest("Fiyat 0 veya daha büyük olmalıdır.");
        if (in.releaseDate() == null) throw ApiException.badRequest("Çıkış tarihi zorunludur.");
        if (in.publisherId() == null) throw ApiException.badRequest("Publisher zorunludur.");
        if (in.genreIds() == null || in.genreIds().isEmpty()) throw ApiException.badRequest("En az bir tür seçilmelidir.");
        if (in.developerIds() == null || in.developerIds().isEmpty()) throw ApiException.badRequest("En az bir geliştirici seçilmelidir.");
        if (creating) {
            if ("BASE_GAME".equals(in.type())) {
                if (in.minimumRequirements() == null || in.minimumRequirements().isBlank())
                    throw ApiException.badRequest("Ana oyun için minimum sistem gereksinimleri zorunludur.");
            } else if ("DLC".equals(in.type())) {
                if (in.baseGameId() == null) throw ApiException.badRequest("DLC için bağlı ana oyun zorunludur.");
                if (in.contentSummary() == null || in.contentSummary().isBlank())
                    throw ApiException.badRequest("DLC için içerik özeti zorunludur.");
            } else {
                throw ApiException.badRequest("type BASE_GAME veya DLC olmalıdır.");
            }
        }
    }

    private static AdminProduct load(Tx tx, long productId) throws SQLException {
        return tx.queryOne("""
                SELECT p.product_id, p.title, p.description, p.list_price, p.release_date, p.is_active,
                       p.publisher_id, pu.name AS publisher_name,
                       CASE WHEN bg.product_id IS NOT NULL THEN 'BASE_GAME' ELSE 'DLC' END AS product_type,
                       bg.minimum_requirements, d.base_game_id, d.content_summary,
                       ARRAY(SELECT developer_id FROM product_developer WHERE product_id = p.product_id ORDER BY 1) AS developer_ids,
                       ARRAY(SELECT genre_id FROM product_genre WHERE product_id = p.product_id ORDER BY 1) AS genre_ids,
                       coalesce((SELECT sum(units_sold) FROM v_game_sales_monthly s WHERE s.product_id = p.product_id), 0) AS units_sold
                  FROM product p
                  JOIN publisher pu      ON pu.publisher_id = p.publisher_id
                  LEFT JOIN base_game bg ON bg.product_id = p.product_id
                  LEFT JOIN dlc d        ON d.product_id = p.product_id
                 WHERE p.product_id = ?
                """, rs -> new AdminProduct(rs.getLong("product_id"), rs.getString("product_type"), rs.getString("title"),
                rs.getString("description"), rs.getBigDecimal("list_price"), rs.getObject("release_date", LocalDate.class),
                rs.getBoolean("is_active"), rs.getLong("publisher_id"), rs.getString("publisher_name"),
                Tx.getLongList(rs, "developer_ids"), Tx.getLongList(rs, "genre_ids"),
                rs.getString("minimum_requirements"), Tx.getLongOrNull(rs, "base_game_id"),
                rs.getString("content_summary"), rs.getLong("units_sold")), productId)
                .orElseThrow(() -> ApiException.notFound("Ürün bulunamadı."));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------------ publisher / developer / genre

    public enum RefKind {
        PUBLISHER("publisher", "publisher_id", true, "SELECT count(*) FROM product WHERE publisher_id = r.publisher_id"),
        DEVELOPER("developer", "developer_id", true, "SELECT count(*) FROM product_developer WHERE developer_id = r.developer_id"),
        GENRE("genre", "genre_id", false, "SELECT count(*) FROM product_genre WHERE genre_id = r.genre_id");

        final String table;
        final String idCol;
        final boolean hasCountry;
        final String countSql;

        RefKind(String table, String idCol, boolean hasCountry, String countSql) {
            this.table = table;
            this.idCol = idCol;
            this.hasCountry = hasCountry;
            this.countSql = countSql;
        }
    }

    public List<Ref> listRefs(long adminId, RefKind k) {
        String sql = "SELECT r." + k.idCol + ", r.name, " + (k.hasCountry ? "r.country" : "NULL::text") + " AS country, ("
                + k.countSql + ") AS product_count FROM " + k.table + " r ORDER BY r.name";
        return db.adminReadOnly(adminId, tx -> tx.query(sql,
                rs -> new Ref(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4))));
    }

    public Ref createRef(long adminId, RefKind k, String name, String country) {
        if (name == null || name.isBlank()) throw ApiException.badRequest("Ad zorunludur.");
        return db.admin(adminId, tx -> {
            long id = k.hasCountry
                    ? tx.insertReturningLong("INSERT INTO " + k.table + " (name, country) VALUES (?, ?) RETURNING " + k.idCol,
                    name.trim(), blankToNull(country))
                    : tx.insertReturningLong("INSERT INTO " + k.table + " (name) VALUES (?) RETURNING " + k.idCol, name.trim());
            return new Ref(id, name.trim(), k.hasCountry ? blankToNull(country) : null, 0);
        });
    }

    public Ref updateRef(long adminId, RefKind k, long id, String name, String country) {
        if (name == null || name.isBlank()) throw ApiException.badRequest("Ad zorunludur.");
        return db.admin(adminId, tx -> {
            int n = k.hasCountry
                    ? tx.update("UPDATE " + k.table + " SET name = ?, country = ? WHERE " + k.idCol + " = ?",
                    name.trim(), blankToNull(country), id)
                    : tx.update("UPDATE " + k.table + " SET name = ? WHERE " + k.idCol + " = ?", name.trim(), id);
            if (n == 0) throw ApiException.notFound("Kayıt bulunamadı.");
            long count = tx.queryLong("SELECT (" + k.countSql + ") FROM " + k.table + " r WHERE r." + k.idCol + " = ?", id);
            return new Ref(id, name.trim(), k.hasCountry ? blankToNull(country) : null, count);
        });
    }

    /** Referans verilen kayıt FK RESTRICT nedeniyle silinemez (409). */
    public void deleteRef(long adminId, RefKind k, long id) {
        db.admin(adminId, tx -> {
            int n = tx.update("DELETE FROM " + k.table + " WHERE " + k.idCol + " = ?", id);
            if (n == 0) throw ApiException.notFound("Kayıt bulunamadı.");
            return null;
        });
    }
}
