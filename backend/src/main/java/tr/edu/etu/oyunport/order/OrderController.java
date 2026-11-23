package tr.edu.etu.oyunport.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tr.edu.etu.oyunport.auth.AuthUser;
import tr.edu.etu.oyunport.common.ApiException;
import tr.edu.etu.oyunport.common.Page;
import tr.edu.etu.oyunport.db.Db;
import tr.edu.etu.oyunport.order.OrderModels.OrderDetail;
import tr.edu.etu.oyunport.order.OrderModels.OrderSummary;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final CheckoutService checkout;
    private final OrderQueries queries;
    private final Db db;

    public OrderController(CheckoutService checkout, OrderQueries queries, Db db) {
        this.checkout = checkout;
        this.queries = queries;
        this.db = db;
    }

    public record CreateOrderRequest(@NotNull UUID checkoutKey,
                                     @NotEmpty @Size(max = CheckoutService.MAX_ITEMS_PER_ORDER) List<@NotNull Long> productIds) {}

    public record PayRequest(@NotNull UUID requestKey, PaymentSimulator.SimulatedOutcome simulateOutcome) {}

    /** T1: sepetten PENDING sipariş. Aynı checkoutKey tekrar gelirse aynı sipariş döner (200). */
    @PostMapping
    public ResponseEntity<OrderModels.CreateOrderResult> create(@AuthenticationPrincipal AuthUser user,
                                                                @Valid @RequestBody CreateOrderRequest r) {
        var result = checkout.createOrder(user.userId(), r.checkoutKey(), r.productIds());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    /** T2: ödeme denemesi. Başarısız simülasyon da 200 döner; sonuç paymentStatus alanındadır. */
    @PostMapping("/{orderId}/payments")
    public OrderModels.PaymentResult pay(@AuthenticationPrincipal AuthUser user, @PathVariable long orderId,
                                         @Valid @RequestBody PayRequest r) {
        return checkout.pay(user.userId(), orderId, r.requestKey(), r.simulateOutcome());
    }

    @PostMapping("/{orderId}/cancel")
    public OrderDetail cancel(@AuthenticationPrincipal AuthUser user, @PathVariable long orderId) {
        checkout.cancel(user.userId(), orderId);
        return detail(user, orderId);
    }

    @GetMapping
    public Page<OrderSummary> list(@AuthenticationPrincipal AuthUser user,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        int s = Page.clampSize(size);
        int p = Math.max(0, page);
        return db.customerReadOnly(user.userId(), tx -> new Page<>(
                queries.listForUser(tx, user.userId(), s, p * s), p, s, queries.countForUser(tx, user.userId())));
    }

    @GetMapping("/{orderId}")
    public OrderDetail detail(@AuthenticationPrincipal AuthUser user, @PathVariable long orderId) {
        return db.customerReadOnly(user.userId(), tx -> queries.findDetail(tx, orderId))
                .orElseThrow(() -> ApiException.notFound("Sipariş bulunamadı."));
    }
}
