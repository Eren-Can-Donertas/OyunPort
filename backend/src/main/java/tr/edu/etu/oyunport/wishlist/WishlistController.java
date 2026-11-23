package tr.edu.etu.oyunport.wishlist;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tr.edu.etu.oyunport.auth.AuthUser;
import tr.edu.etu.oyunport.common.ApiException;
import tr.edu.etu.oyunport.db.Db;
import tr.edu.etu.oyunport.db.Tx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** İstek listesi (FR09). WISHLIST(user_id, product_id) PK; RLS ile yalnızca kendi listesi. */
@RestController
@RequestMapping("/api/wishlist")
public class WishlistController {

    private final Db db;

    public WishlistController(Db db) {
        this.db = db;
    }

    public record WishlistItem(long productId, String title, String productType, BigDecimal listPrice,
                               boolean active, boolean owned, Instant addedAt) {}

    @GetMapping
    public List<WishlistItem> list(@AuthenticationPrincipal AuthUser user) {
        return db.customerReadOnly(user.userId(), tx -> tx.query("""
                SELECT w.product_id, p.title, p.list_price, p.is_active, w.added_at,
                       CASE WHEN bg.product_id IS NOT NULL THEN 'BASE_GAME' ELSE 'DLC' END AS product_type,
                       EXISTS (SELECT 1 FROM library_entry le
                                WHERE le.user_id = w.user_id AND le.product_id = w.product_id) AS owned
                  FROM wishlist w
                  JOIN product p ON p.product_id = w.product_id
                  LEFT JOIN base_game bg ON bg.product_id = p.product_id
                 WHERE w.user_id = ?
                 ORDER BY w.added_at DESC
                """, rs -> new WishlistItem(rs.getLong("product_id"), rs.getString("title"),
                rs.getString("product_type"), rs.getBigDecimal("list_price"), rs.getBoolean("is_active"),
                rs.getBoolean("owned"), Tx.getInstant(rs, "added_at")), user.userId()));
    }

    @PutMapping("/{productId}")
    public ResponseEntity<Void> add(@AuthenticationPrincipal AuthUser user, @PathVariable long productId) {
        db.customer(user.userId(), tx -> {
            boolean active = tx.queryOne("SELECT is_active FROM product WHERE product_id = ?",
                    rs -> rs.getBoolean(1), productId).orElseThrow(() -> ApiException.notFound("Ürün bulunamadı."));
            if (!active) {
                throw ApiException.unprocessable("PRODUCT_INACTIVE", "Pasif ürün istek listesine eklenemez.");
            }
            return tx.update("""
                    INSERT INTO wishlist (user_id, product_id) VALUES (?, ?)
                    ON CONFLICT (user_id, product_id) DO NOTHING
                    """, user.userId(), productId);
        });
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal AuthUser user, @PathVariable long productId) {
        int n = db.customer(user.userId(), tx -> tx.update(
                "DELETE FROM wishlist WHERE user_id = ? AND product_id = ?", user.userId(), productId));
        if (n == 0) {
            throw ApiException.notFound("Ürün istek listenizde değil.");
        }
        return ResponseEntity.noContent().build();
    }
}
