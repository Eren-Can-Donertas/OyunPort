package tr.edu.etu.oyunport.review;

import org.springframework.stereotype.Service;
import tr.edu.etu.oyunport.common.ApiException;
import tr.edu.etu.oyunport.db.Db;
import tr.edu.etu.oyunport.db.Tx;

import java.time.Instant;
import java.util.List;

/**
 * Puan ve yorum (FR08, BR06). Servis katmanı anlaşılır hata verir; asıl garanti veritabanında:
 * rating CHECK 1–5, PK(user_id, base_game_id) tek yorum, FK (user_id, base_game_id) ->
 * LIBRARY_ENTRY(user_id, product_id) sahiplik, FK base_game_id -> BASE_GAME.
 */
@Service
public class ReviewService {

    private final Db db;

    public ReviewService(Db db) {
        this.db = db;
    }

    public record ReviewView(long baseGameId, String title, int rating, String comment,
                             Instant createdAt, Instant updatedAt, boolean created) {}

    public ReviewView upsert(long userId, long baseGameId, int rating, String comment) {
        String c = comment == null || comment.isBlank() ? null : comment.trim();
        return db.customer(userId, tx -> {
            String title = tx.queryOne("""
                    SELECT p.title FROM base_game bg JOIN product p ON p.product_id = bg.product_id
                     WHERE bg.product_id = ?
                    """, rs -> rs.getString(1), baseGameId)
                    .orElseThrow(() -> ApiException.unprocessable("NOT_A_BASE_GAME",
                            "[BR06] Yorum yalnızca ana oyunlar (BASE_GAME) için yapılabilir."));
            boolean owned = tx.queryOne("SELECT 1 FROM library_entry WHERE user_id = ? AND product_id = ?",
                    rs -> 1, userId, baseGameId).isPresent();
            if (!owned) {
                throw ApiException.forbidden("[BR06] Yalnızca sahip olduğunuz ana oyuna yorum yapabilirsiniz.");
            }
            return tx.queryOne("""
                    INSERT INTO review (user_id, base_game_id, rating, comment)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (user_id, base_game_id)
                    DO UPDATE SET rating = EXCLUDED.rating, comment = EXCLUDED.comment
                    RETURNING base_game_id, rating, comment, created_at, updated_at, (xmax = 0) AS inserted
                    """, rs -> new ReviewView(rs.getLong("base_game_id"), title, rs.getInt("rating"),
                    rs.getString("comment"), Tx.getInstant(rs, "created_at"), Tx.getInstant(rs, "updated_at"),
                    rs.getBoolean("inserted")), userId, baseGameId, rating, c).orElseThrow();
        });
    }

    public List<ReviewView> mine(long userId) {
        return db.customerReadOnly(userId, tx -> tx.query("""
                SELECT r.base_game_id, p.title, r.rating, r.comment, r.created_at, r.updated_at
                  FROM review r JOIN product p ON p.product_id = r.base_game_id
                 WHERE r.user_id = ?
                 ORDER BY r.updated_at DESC
                """, rs -> new ReviewView(rs.getLong("base_game_id"), rs.getString("title"), rs.getInt("rating"),
                rs.getString("comment"), Tx.getInstant(rs, "created_at"), Tx.getInstant(rs, "updated_at"), false),
                userId));
    }
}
