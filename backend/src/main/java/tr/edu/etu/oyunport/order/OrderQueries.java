package tr.edu.etu.oyunport.order;

import org.springframework.stereotype.Component;
import tr.edu.etu.oyunport.db.Tx;
import tr.edu.etu.oyunport.order.OrderModels.OrderDetail;
import tr.edu.etu.oyunport.order.OrderModels.OrderLine;
import tr.edu.etu.oyunport.order.OrderModels.OrderSummary;
import tr.edu.etu.oyunport.order.OrderModels.PaymentView;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sipariş okuma sorguları. app_customer havuzunda RLS yalnızca oturum sahibinin siparişlerini döndürür. */
@Component
public class OrderQueries {

    private record OrderHead(long orderId, String status, UUID checkoutKey, Instant createdAt,
                             Instant completedAt, int itemCount, BigDecimal total) {}

    public Optional<OrderDetail> findDetail(Tx tx, long orderId) throws SQLException {
        Optional<OrderHead> head = tx.queryOne("""
                SELECT order_id, status, checkout_key, created_at, completed_at, item_count, order_total
                  FROM v_order_summary
                 WHERE order_id = ?
                """, rs -> new OrderHead(rs.getLong("order_id"), rs.getString("status"),
                rs.getObject("checkout_key", UUID.class), Tx.getInstant(rs, "created_at"),
                Tx.getInstant(rs, "completed_at"), rs.getInt("item_count"), rs.getBigDecimal("order_total")),
                orderId);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        OrderHead h = head.get();
        List<OrderLine> items = tx.query("""
                SELECT oi.line_no, oi.product_id, p.title, oi.unit_price_paid, p.list_price,
                       CASE WHEN bg.product_id IS NOT NULL THEN 'BASE_GAME' ELSE 'DLC' END AS product_type
                  FROM order_item oi
                  JOIN product p         ON p.product_id = oi.product_id
                  LEFT JOIN base_game bg ON bg.product_id = oi.product_id
                 WHERE oi.order_id = ?
                 ORDER BY oi.line_no
                """, rs -> new OrderLine(rs.getInt("line_no"), rs.getLong("product_id"), rs.getString("title"),
                rs.getString("product_type"), rs.getBigDecimal("unit_price_paid"), rs.getBigDecimal("list_price")),
                orderId);
        List<PaymentView> payments = tx.query("""
                SELECT payment_id, amount, status, attempted_at, request_key
                  FROM payment_attempt
                 WHERE order_id = ?
                 ORDER BY attempted_at, payment_id
                """, rs -> new PaymentView(rs.getLong("payment_id"), rs.getBigDecimal("amount"),
                rs.getString("status"), Tx.getInstant(rs, "attempted_at"), rs.getObject("request_key", UUID.class)),
                orderId);
        return Optional.of(new OrderDetail(h.orderId(), h.status(), h.checkoutKey(), h.createdAt(),
                h.completedAt(), h.itemCount(), h.total(), items, payments));
    }

    public List<OrderSummary> listForUser(Tx tx, long userId, int limit, int offset) throws SQLException {
        return tx.query("""
                SELECT order_id, status, created_at, completed_at, item_count, order_total
                  FROM v_order_summary
                 WHERE user_id = ?
                 ORDER BY created_at DESC, order_id DESC
                 LIMIT ? OFFSET ?
                """, rs -> new OrderSummary(rs.getLong("order_id"), rs.getString("status"),
                Tx.getInstant(rs, "created_at"), Tx.getInstant(rs, "completed_at"),
                rs.getInt("item_count"), rs.getBigDecimal("order_total")), userId, limit, offset);
    }

    public long countForUser(Tx tx, long userId) throws SQLException {
        return tx.queryLong("SELECT count(*) FROM store_order WHERE user_id = ?", userId);
    }
}
