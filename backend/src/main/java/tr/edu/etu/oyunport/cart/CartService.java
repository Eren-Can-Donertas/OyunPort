package tr.edu.etu.oyunport.cart;

import org.springframework.stereotype.Service;
import tr.edu.etu.oyunport.db.Db;
import tr.edu.etu.oyunport.db.Tx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sepet doğrulaması (FR04). Şemada sepet tablosu yoktur (Tasarım notları Ç1); sepet
 * istemcide tutulur ve her değişiklikte burada sunucu tarafında doğrulanır:
 * güncel fiyat, aktiflik, sahiplik (BR02), sepette tekrar (BR03), DLC ön koşulu (BR05).
 * Aynı kontroller T1 ve T2'de transaction içinde tekrar yapılır.
 */
@Service
public class CartService {

    private final Db db;

    public CartService(Db db) {
        this.db = db;
    }

    public enum ItemStatus { OK, NOT_FOUND, INACTIVE, OWNED, DUPLICATE, DLC_REQUIRES_BASE_GAME }

    public record CartItem(long productId, String title, String productType, BigDecimal listPrice,
                           Long baseGameId, String baseGameTitle, ItemStatus status, String message) {}

    public record CartValidation(List<CartItem> items, BigDecimal total, boolean valid) {}

    private record Row(long productId, String title, BigDecimal listPrice, boolean active, boolean baseGame,
                       Long baseGameId, String baseGameTitle, boolean owned) {}

    public CartValidation validate(long userId, List<Long> productIds) {
        List<Long> ids = productIds == null ? List.of() : productIds;
        return db.customerReadOnly(userId, tx -> {
            Map<Long, Row> rows = tx.query("""
                    SELECT p.product_id, p.title, p.list_price, p.is_active,
                           (bg.product_id IS NOT NULL) AS is_base_game,
                           d.base_game_id, bp.title AS base_game_title,
                           EXISTS (SELECT 1 FROM library_entry le
                                    WHERE le.user_id = ? AND le.product_id = p.product_id) AS owned
                      FROM product p
                      LEFT JOIN base_game bg ON bg.product_id = p.product_id
                      LEFT JOIN dlc d        ON d.product_id = p.product_id
                      LEFT JOIN product bp   ON bp.product_id = d.base_game_id
                     WHERE p.product_id = ANY(?)
                    """, rs -> new Row(rs.getLong("product_id"), rs.getString("title"), rs.getBigDecimal("list_price"),
                    rs.getBoolean("is_active"), rs.getBoolean("is_base_game"), Tx.getLongOrNull(rs, "base_game_id"),
                    rs.getString("base_game_title"), rs.getBoolean("owned")), userId, ids)
                    .stream().collect(Collectors.toMap(Row::productId, Function.identity()));

            Set<Long> cartSet = new LinkedHashSet<>(ids);
            Set<Long> seen = new HashSet<>();
            List<CartItem> items = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            boolean valid = !ids.isEmpty();

            for (Long pid : ids) {
                Row r = rows.get(pid);
                if (r == null) {
                    items.add(new CartItem(pid, null, null, null, null, null, ItemStatus.NOT_FOUND, "Ürün bulunamadı."));
                    valid = false;
                    continue;
                }
                String type = r.baseGame() ? "BASE_GAME" : "DLC";
                ItemStatus status = ItemStatus.OK;
                String msg = null;
                if (!seen.add(pid)) {
                    status = ItemStatus.DUPLICATE;
                    msg = "[BR03] Aynı ürün sepette iki kez bulunamaz.";
                } else if (!r.active()) {
                    status = ItemStatus.INACTIVE;
                    msg = "Ürün satışta değil.";
                } else if (r.owned()) {
                    status = ItemStatus.OWNED;
                    msg = "[BR02] Bu ürüne zaten sahipsiniz.";
                } else if (r.baseGameId() != null && !cartSet.contains(r.baseGameId())
                        && !owns(tx, userId, r.baseGameId())) {
                    status = ItemStatus.DLC_REQUIRES_BASE_GAME;
                    msg = "[BR05] Önce '" + r.baseGameTitle() + "' ana oyununa sahip olmalı veya sepete eklemelisiniz.";
                }
                if (status == ItemStatus.OK) {
                    total = total.add(r.listPrice());
                } else {
                    valid = false;
                }
                items.add(new CartItem(pid, r.title(), type, r.listPrice(), r.baseGameId(), r.baseGameTitle(), status, msg));
            }
            return new CartValidation(items, total, valid);
        });
    }

    private static boolean owns(Tx tx, long userId, long productId) throws java.sql.SQLException {
        return tx.queryOne("SELECT 1 FROM library_entry WHERE user_id = ? AND product_id = ?",
                rs -> 1, userId, productId).isPresent();
    }
}
