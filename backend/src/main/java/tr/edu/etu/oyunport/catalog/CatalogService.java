package tr.edu.etu.oyunport.catalog;

import org.springframework.stereotype.Service;
import tr.edu.etu.oyunport.common.ApiException;
import tr.edu.etu.oyunport.common.Page;
import tr.edu.etu.oyunport.db.Db;
import tr.edu.etu.oyunport.db.Tx;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Katalog okuma (FR02, FR03). Anonim kullanıcılar da erişir; oturum varsa sahiplik ve
 * istek listesi bayrakları RLS altında kullanıcının kendi kayıtlarından okunur.
 * Arama koşulları dinamik kurulur ama bütün değerler bind parametresidir; sıralama beyaz listedir.
 */
@Service
public class CatalogService {

    private final Db db;

    public CatalogService(Db db) {
        this.db = db;
    }

    public record SearchCriteria(String q, Long genreId, Long publisherId, Long developerId,
                                 BigDecimal minPrice, BigDecimal maxPrice, String type, String sort,
                                 int page, int size) {}

    public record ProductCard(long productId, String title, String productType, BigDecimal listPrice,
                              LocalDate releaseDate, long publisherId, String publisherName,
                              Long baseGameId, String baseGameTitle, BigDecimal avgRating, int reviewCount,
                              List<String> genres, boolean owned, boolean wishlisted) {}

    public record NamedRef(long id, String name) {}

    public record DlcRef(long productId, String title, BigDecimal listPrice, boolean active, boolean owned) {}

    public record ReviewView(String displayName, int rating, String comment, Instant createdAt, Instant updatedAt) {}

    public record MyReview(int rating, String comment, Instant updatedAt) {}

    public record ProductDetail(long productId, String title, String description, String productType,
                                BigDecimal listPrice, LocalDate releaseDate, boolean active,
                                NamedRef publisher, String publisherCountry,
                                List<NamedRef> developers, List<NamedRef> genres,
                                String minimumRequirements, String contentSummary,
                                NamedRef baseGame, List<DlcRef> dlcs,
                                BigDecimal avgRating, int reviewCount, List<ReviewView> recentReviews,
                                boolean owned, boolean ownsBaseGame, boolean wishlisted, MyReview myReview) {}

    private static final Map<String, String> SORTS = Map.of(
            "title", "p.title ASC, p.product_id",
            "price_asc", "p.list_price ASC, p.product_id",
            "price_desc", "p.list_price DESC, p.product_id",
            "release_desc", "p.release_date DESC, p.product_id",
            "rating_desc", "r.avg_rating DESC NULLS LAST, r.review_count DESC NULLS LAST, p.product_id");

    public Page<ProductCard> search(Long userId, SearchCriteria c) {
        StringBuilder where = new StringBuilder(" WHERE p.is_active ");
        List<Object> params = new ArrayList<>();
        params.add(userId);   // owned
        params.add(userId);   // wishlisted
        if (c.q() != null && !c.q().isBlank()) {
            where.append(" AND p.title ILIKE ? ");
            params.add("%" + escapeLike(c.q().trim()) + "%");
        }
        if (c.genreId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM product_genre pg WHERE pg.product_id = p.product_id AND pg.genre_id = ?) ");
            params.add(c.genreId());
        }
        if (c.developerId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM product_developer pd WHERE pd.product_id = p.product_id AND pd.developer_id = ?) ");
            params.add(c.developerId());
        }
        if (c.publisherId() != null) {
            where.append(" AND p.publisher_id = ? ");
            params.add(c.publisherId());
        }
        if (c.minPrice() != null) {
            where.append(" AND p.list_price >= ? ");
            params.add(c.minPrice());
        }
        if (c.maxPrice() != null) {
            where.append(" AND p.list_price <= ? ");
            params.add(c.maxPrice());
        }
        if ("BASE_GAME".equals(c.type())) {
            where.append(" AND bg.product_id IS NOT NULL ");
        } else if ("DLC".equals(c.type())) {
            where.append(" AND d.product_id IS NOT NULL ");
        } else if (c.type() != null && !c.type().isBlank()) {
            throw ApiException.badRequest("type yalnızca BASE_GAME veya DLC olabilir.");
        }
        String orderBy = SORTS.getOrDefault(c.sort() == null ? "title" : c.sort(), null);
        if (orderBy == null) {
            throw ApiException.badRequest("Geçersiz sıralama: " + c.sort());
        }
        int size = Page.clampSize(c.size());
        int page = Math.max(0, c.page());
        params.add(size);
        params.add(page * size);

        String sql = """
                SELECT p.product_id, p.title, p.list_price, p.release_date,
                       pu.publisher_id, pu.name AS publisher_name,
                       CASE WHEN bg.product_id IS NOT NULL THEN 'BASE_GAME' ELSE 'DLC' END AS product_type,
                       d.base_game_id, bp.title AS base_game_title,
                       r.avg_rating, coalesce(r.review_count, 0) AS review_count,
                       ARRAY(SELECT g.name FROM product_genre pg JOIN genre g ON g.genre_id = pg.genre_id
                              WHERE pg.product_id = p.product_id ORDER BY g.name) AS genres,
                       EXISTS (SELECT 1 FROM library_entry le WHERE le.user_id = ? AND le.product_id = p.product_id) AS owned,
                       EXISTS (SELECT 1 FROM wishlist w WHERE w.user_id = ? AND w.product_id = p.product_id) AS wishlisted,
                       count(*) OVER () AS total_count
                  FROM product p
                  JOIN publisher pu      ON pu.publisher_id = p.publisher_id
                  LEFT JOIN base_game bg ON bg.product_id = p.product_id
                  LEFT JOIN dlc d        ON d.product_id = p.product_id
                  LEFT JOIN product bp   ON bp.product_id = d.base_game_id
                  LEFT JOIN v_product_rating r ON r.product_id = p.product_id
                """ + where + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?";

        return db.customerReadOnly(userId, tx -> {
            long[] total = {0};
            List<ProductCard> items = tx.query(sql, rs -> {
                total[0] = rs.getLong("total_count");
                return mapCard(rs);
            }, params.toArray());
            if (items.isEmpty() && page > 0) {
                total[0] = tx.queryLong("SELECT count(*) FROM product p LEFT JOIN base_game bg ON bg.product_id = p.product_id "
                        + "LEFT JOIN dlc d ON d.product_id = p.product_id" + where,
                        params.subList(2, params.size() - 2).toArray());
            }
            return new Page<>(items, page, size, total[0]);
        });
    }

    private static ProductCard mapCard(ResultSet rs) throws SQLException {
        return new ProductCard(rs.getLong("product_id"), rs.getString("title"), rs.getString("product_type"),
                rs.getBigDecimal("list_price"), rs.getObject("release_date", LocalDate.class),
                rs.getLong("publisher_id"), rs.getString("publisher_name"),
                Tx.getLongOrNull(rs, "base_game_id"), rs.getString("base_game_title"),
                rs.getBigDecimal("avg_rating"), rs.getInt("review_count"),
                Tx.getStringList(rs, "genres"), rs.getBoolean("owned"), rs.getBoolean("wishlisted"));
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public ProductDetail detail(Long userId, long productId) {
        return db.customerReadOnly(userId, tx -> {
            record Head(long id, String title, String description, String type, BigDecimal price, LocalDate release,
                        boolean active, long publisherId, String publisherName, String publisherCountry,
                        String requirements, String summary, Long baseGameId, String baseGameTitle,
                        BigDecimal avgRating, int reviewCount) {}
            Head h = tx.queryOne("""
                    SELECT p.product_id, p.title, p.description, p.list_price, p.release_date, p.is_active,
                           pu.publisher_id, pu.name AS publisher_name, pu.country AS publisher_country,
                           CASE WHEN bg.product_id IS NOT NULL THEN 'BASE_GAME' ELSE 'DLC' END AS product_type,
                           bg.minimum_requirements, d.content_summary, d.base_game_id, bp.title AS base_game_title,
                           r.avg_rating, coalesce(r.review_count, 0) AS review_count
                      FROM product p
                      JOIN publisher pu      ON pu.publisher_id = p.publisher_id
                      LEFT JOIN base_game bg ON bg.product_id = p.product_id
                      LEFT JOIN dlc d        ON d.product_id = p.product_id
                      LEFT JOIN product bp   ON bp.product_id = d.base_game_id
                      LEFT JOIN v_product_rating r ON r.product_id = p.product_id
                     WHERE p.product_id = ?
                    """, rs -> new Head(rs.getLong("product_id"), rs.getString("title"), rs.getString("description"),
                    rs.getString("product_type"), rs.getBigDecimal("list_price"),
                    rs.getObject("release_date", LocalDate.class), rs.getBoolean("is_active"),
                    rs.getLong("publisher_id"), rs.getString("publisher_name"), rs.getString("publisher_country"),
                    rs.getString("minimum_requirements"), rs.getString("content_summary"),
                    Tx.getLongOrNull(rs, "base_game_id"), rs.getString("base_game_title"),
                    rs.getBigDecimal("avg_rating"), rs.getInt("review_count")), productId)
                    .orElseThrow(() -> ApiException.notFound("Ürün bulunamadı."));

            List<NamedRef> developers = tx.query("""
                    SELECT dv.developer_id, dv.name FROM product_developer pd
                      JOIN developer dv ON dv.developer_id = pd.developer_id
                     WHERE pd.product_id = ? ORDER BY dv.name
                    """, rs -> new NamedRef(rs.getLong(1), rs.getString(2)), productId);
            List<NamedRef> genres = tx.query("""
                    SELECT g.genre_id, g.name FROM product_genre pg JOIN genre g ON g.genre_id = pg.genre_id
                     WHERE pg.product_id = ? ORDER BY g.name
                    """, rs -> new NamedRef(rs.getLong(1), rs.getString(2)), productId);
            List<DlcRef> dlcs = "BASE_GAME".equals(h.type()) ? tx.query("""
                    SELECT p.product_id, p.title, p.list_price, p.is_active,
                           EXISTS (SELECT 1 FROM library_entry le WHERE le.user_id = ? AND le.product_id = p.product_id) AS owned
                      FROM dlc d JOIN product p ON p.product_id = d.product_id
                     WHERE d.base_game_id = ? ORDER BY p.release_date, p.title
                    """, rs -> new DlcRef(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getBoolean(4),
                    rs.getBoolean(5)), userId, productId) : List.of();
            long reviewTarget = "BASE_GAME".equals(h.type()) ? productId : -1;
            List<ReviewView> reviews = tx.query("""
                    SELECT display_name, rating, comment, created_at, updated_at
                      FROM v_public_review WHERE base_game_id = ?
                     ORDER BY updated_at DESC LIMIT 10
                    """, CatalogService::mapReview, reviewTarget);

            boolean owned = userId != null && owns(tx, userId, productId);
            boolean ownsBase = userId != null && h.baseGameId() != null && owns(tx, userId, h.baseGameId());
            boolean wishlisted = userId != null && tx.queryOne(
                    "SELECT 1 FROM wishlist WHERE user_id = ? AND product_id = ?", rs -> 1, userId, productId).isPresent();
            MyReview myReview = userId == null ? null : tx.queryOne(
                    "SELECT rating, comment, updated_at FROM review WHERE user_id = ? AND base_game_id = ?",
                    rs -> new MyReview(rs.getInt(1), rs.getString(2), Tx.getInstant(rs, "updated_at")),
                    userId, productId).orElse(null);

            return new ProductDetail(h.id(), h.title(), h.description(), h.type(), h.price(), h.release(), h.active(),
                    new NamedRef(h.publisherId(), h.publisherName()), h.publisherCountry(), developers, genres,
                    h.requirements(), h.summary(),
                    h.baseGameId() == null ? null : new NamedRef(h.baseGameId(), h.baseGameTitle()), dlcs,
                    h.avgRating(), h.reviewCount(), reviews, owned, ownsBase, wishlisted, myReview);
        });
    }

    public Page<ReviewView> reviews(long productId, int page, int size) {
        int s = Page.clampSize(size);
        int p = Math.max(0, page);
        return db.customerReadOnly(null, tx -> new Page<>(
                tx.query("""
                        SELECT display_name, rating, comment, created_at, updated_at
                          FROM v_public_review WHERE base_game_id = ?
                         ORDER BY updated_at DESC LIMIT ? OFFSET ?
                        """, CatalogService::mapReview, productId, s, p * s),
                p, s, tx.queryLong("SELECT count(*) FROM v_public_review WHERE base_game_id = ?", productId)));
    }

    private static ReviewView mapReview(ResultSet rs) throws SQLException {
        return new ReviewView(rs.getString("display_name"), rs.getInt("rating"), rs.getString("comment"),
                Tx.getInstant(rs, "created_at"), Tx.getInstant(rs, "updated_at"));
    }

    public List<NamedRef> genres() {
        return db.customerReadOnly(null, tx -> tx.query("SELECT genre_id, name FROM genre ORDER BY name",
                rs -> new NamedRef(rs.getLong(1), rs.getString(2))));
    }

    public List<NamedRef> publishers() {
        return db.customerReadOnly(null, tx -> tx.query("SELECT publisher_id, name FROM publisher ORDER BY name",
                rs -> new NamedRef(rs.getLong(1), rs.getString(2))));
    }

    public List<NamedRef> developers() {
        return db.customerReadOnly(null, tx -> tx.query("SELECT developer_id, name FROM developer ORDER BY name",
                rs -> new NamedRef(rs.getLong(1), rs.getString(2))));
    }

    private static boolean owns(Tx tx, long userId, long productId) throws SQLException {
        return tx.queryOne("SELECT 1 FROM library_entry WHERE user_id = ? AND product_id = ?",
                rs -> 1, userId, productId).isPresent();
    }
}
