package tr.edu.etu.oyunport.cart;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tr.edu.etu.oyunport.auth.AuthUser;
import tr.edu.etu.oyunport.order.CheckoutService;

import java.util.List;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    public record ValidateRequest(@NotNull @Size(max = CheckoutService.MAX_ITEMS_PER_ORDER) List<@NotNull Long> productIds) {}

    @PostMapping("/validate")
    public CartService.CartValidation validate(@AuthenticationPrincipal AuthUser user,
                                               @Valid @RequestBody ValidateRequest r) {
        return cart.validate(user.userId(), r.productIds());
    }
}
