package tr.edu.etu.oyunport.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Sipariş/ödeme API modelleri. */
public final class OrderModels {

    private OrderModels() {}

    public record OrderLine(int lineNo, long productId, String title, String productType,
                            BigDecimal unitPricePaid, BigDecimal currentListPrice) {}

    public record PaymentView(long paymentId, BigDecimal amount, String status, Instant attemptedAt, UUID requestKey) {}

    public record OrderDetail(long orderId, String status, UUID checkoutKey, Instant createdAt, Instant completedAt,
                              int itemCount, BigDecimal total, List<OrderLine> items, List<PaymentView> payments) {}

    public record OrderSummary(long orderId, String status, Instant createdAt, Instant completedAt,
                               int itemCount, BigDecimal total) {}

    public record CreateOrderResult(boolean replayed, OrderDetail order) {}

    public record PaymentResult(long paymentId, long orderId, String paymentStatus, String orderStatus,
                                BigDecimal amount, Instant attemptedAt, UUID requestKey, boolean replayed,
                                String failureReason, List<Long> libraryIds) {}
}
