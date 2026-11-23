package tr.edu.etu.oyunport.review;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tr.edu.etu.oyunport.auth.AuthUser;

import java.util.List;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    /**
     * Not: rating için Bean Validation (1–5) bilinçli olarak burada da var; ancak veritabanı
     * CHECK kısıtı bağımsız olarak aynı kuralı uygular (bkz. testler).
     */
    public record ReviewRequest(@Min(1) @Max(5) int rating, @Size(max = 2000) String comment) {}

    @PutMapping("/{baseGameId}")
    public ReviewService.ReviewView upsert(@AuthenticationPrincipal AuthUser user, @PathVariable long baseGameId,
                                           @Valid @RequestBody ReviewRequest r) {
        return reviews.upsert(user.userId(), baseGameId, r.rating(), r.comment());
    }

    @GetMapping("/mine")
    public List<ReviewService.ReviewView> mine(@AuthenticationPrincipal AuthUser user) {
        return reviews.mine(user.userId());
    }
}
