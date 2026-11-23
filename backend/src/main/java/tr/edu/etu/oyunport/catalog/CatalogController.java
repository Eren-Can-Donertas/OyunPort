package tr.edu.etu.oyunport.catalog;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tr.edu.etu.oyunport.auth.AuthUser;
import tr.edu.etu.oyunport.common.Page;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/products")
    public Page<CatalogService.ProductCard> search(@AuthenticationPrincipal AuthUser user,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(required = false) Long genreId,
                                                   @RequestParam(required = false) Long publisherId,
                                                   @RequestParam(required = false) Long developerId,
                                                   @RequestParam(required = false) BigDecimal minPrice,
                                                   @RequestParam(required = false) BigDecimal maxPrice,
                                                   @RequestParam(required = false) String type,
                                                   @RequestParam(defaultValue = "title") String sort,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "24") int size) {
        return catalog.search(user == null ? null : user.userId(),
                new CatalogService.SearchCriteria(q, genreId, publisherId, developerId, minPrice, maxPrice,
                        type, sort, page, size));
    }

    @GetMapping("/products/{productId}")
    public CatalogService.ProductDetail detail(@AuthenticationPrincipal AuthUser user, @PathVariable long productId) {
        return catalog.detail(user == null ? null : user.userId(), productId);
    }

    @GetMapping("/products/{productId}/reviews")
    public Page<CatalogService.ReviewView> reviews(@PathVariable long productId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return catalog.reviews(productId, page, size);
    }

    @GetMapping("/genres")
    public List<CatalogService.NamedRef> genres() {
        return catalog.genres();
    }

    @GetMapping("/publishers")
    public List<CatalogService.NamedRef> publishers() {
        return catalog.publishers();
    }

    @GetMapping("/developers")
    public List<CatalogService.NamedRef> developers() {
        return catalog.developers();
    }
}
