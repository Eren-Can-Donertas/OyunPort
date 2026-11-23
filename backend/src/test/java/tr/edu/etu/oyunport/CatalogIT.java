package tr.edu.etu.oyunport;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tr.edu.etu.oyunport.support.Api;
import tr.edu.etu.oyunport.support.Fixtures;
import tr.edu.etu.oyunport.support.IntegrationTestBase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Katalog arama/filtreleme (FR02) ve ürün detayı (FR03). */
class CatalogIT extends IntegrationTestBase {

    private List<String> titles(Api.Resp r) {
        List<String> out = new ArrayList<>();
        r.body().get("items").forEach(i -> out.add(i.get("title").asText()));
        return out;
    }

    @Test
    @DisplayName("FR02: ad, genre, publisher, fiyat aralığı ve tür (BASE_GAME/DLC) ile filtreleme; fiyat sıralaması")
    void searchAndFilter() {
        String tag = "Zx" + uniq();
        Fixtures.Catalog c = fx.catalog();
        Fixtures.Catalog other = fx.catalog();
        long cheap = fx.baseGame(c, tag + " Ucuz", "50.00");
        long pricey = fx.baseGame(c, tag + " Pahalı", "500.00");
        long dlc = fx.dlc(c, cheap, tag + " Ek Paket", "20.00");
        long foreign = fx.baseGame(other, tag + " Başka Yayıncı", "100.00");

        Api.Resp byName = api.get("/api/catalog/products?q=" + tag + "&size=50", null);
        assertThat(byName.body().get("total").asLong()).isEqualTo(4);

        Api.Resp byGenre = api.get("/api/catalog/products?genreId=" + c.genreId(), null);
        assertThat(byGenre.body().get("total").asLong()).isEqualTo(3);

        Api.Resp byPublisher = api.get("/api/catalog/products?publisherId=" + other.publisherId(), null);
        assertThat(byPublisher.body().get("total").asLong()).isEqualTo(1);
        assertThat(byPublisher.body().at("/items/0/productId").asLong()).isEqualTo(foreign);

        Api.Resp byDeveloper = api.get("/api/catalog/products?developerId=" + c.developerId(), null);
        assertThat(byDeveloper.body().get("total").asLong()).isEqualTo(3);

        Api.Resp priceRange = api.get("/api/catalog/products?q=" + tag + "&minPrice=40&maxPrice=150", null);
        assertThat(titles(priceRange)).allMatch(t -> t.contains("Ucuz") || t.contains("Başka"));
        assertThat(priceRange.body().get("total").asLong()).isEqualTo(2);

        Api.Resp onlyDlc = api.get("/api/catalog/products?q=" + tag + "&type=DLC", null);
        assertThat(onlyDlc.body().get("total").asLong()).isEqualTo(1);
        assertThat(onlyDlc.body().at("/items/0/productId").asLong()).isEqualTo(dlc);
        assertThat(onlyDlc.body().at("/items/0/baseGameId").asLong()).isEqualTo(cheap);

        Api.Resp sorted = api.get("/api/catalog/products?q=" + tag + "&sort=price_desc", null);
        assertThat(sorted.body().at("/items/0/productId").asLong()).isEqualTo(pricey);

        Api.Resp paged = api.get("/api/catalog/products?q=" + tag + "&size=2&page=1&sort=title", null);
        assertThat(paged.body().get("items")).hasSize(2);
        assertThat(paged.body().get("total").asLong()).isEqualTo(4);

        assertThat(api.get("/api/catalog/products?type=XYZ", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("Pasif ürün (BR08) katalog aramasında listelenmez")
    void inactiveProductsHidden() {
        String tag = "Pv" + uniq();
        Fixtures.Catalog c = fx.catalog();
        long g = fx.baseGame(c, tag + " Pasif", "10");
        assertThat(api.get("/api/catalog/products?q=" + tag, null).body().get("total").asLong()).isEqualTo(1);
        fx.sql("UPDATE product SET is_active = false WHERE product_id = ?", g);
        assertThat(api.get("/api/catalog/products?q=" + tag, null).body().get("total").asLong()).isZero();
        assertThat(api.get("/api/catalog/products/" + g, null).body().get("active").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("FR03: ana oyun detayında gereksinimler, developer/genre, DLC listesi; DLC detayında bağlı ana oyun")
    void productDetail() {
        Fixtures.Catalog c = fx.catalog();
        long base = fx.baseGame(c, "Detay Oyunu", "300.00");
        long dlc = fx.dlc(c, base, "Detay DLC", "60.00");

        JsonNode b = api.get("/api/catalog/products/" + base, null).body();
        assertThat(b.get("productType").asText()).isEqualTo("BASE_GAME");
        assertThat(b.get("minimumRequirements").asText()).contains("RAM");
        assertThat(b.get("developers")).hasSize(1);
        assertThat(b.get("genres")).hasSize(1);
        assertThat(b.at("/publisher/id").asLong()).isEqualTo(c.publisherId());
        assertThat(b.get("dlcs")).hasSize(1);
        assertThat(b.at("/dlcs/0/productId").asLong()).isEqualTo(dlc);
        assertThat(b.get("owned").asBoolean()).isFalse();

        JsonNode d = api.get("/api/catalog/products/" + dlc, null).body();
        assertThat(d.get("productType").asText()).isEqualTo("DLC");
        assertThat(d.at("/baseGame/id").asLong()).isEqualTo(base);
        assertThat(d.get("contentSummary").asText()).isNotBlank();

        assertThat(api.get("/api/catalog/products/987654321", null).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("FR03: oturum açmış kullanıcıya sahiplik, istek listesi, yorumlar ve ortalama puan gösterilir")
    void detailForLoggedInUser() {
        Fixtures.Catalog c = fx.catalog();
        long base = fx.baseGame(c, "Puanlı Oyun", "100.00");
        Api.Session s = newCustomer("Yorumcu Ayşe");
        long orderId = api.post("/api/orders", s, java.util.Map.of("checkoutKey", UUID.randomUUID().toString(),
                "productIds", List.of(base))).body().at("/order/orderId").asLong();
        api.post("/api/orders/" + orderId + "/payments", s, java.util.Map.of("requestKey", UUID.randomUUID().toString(), "simulateOutcome", "SUCCESS"));
        api.put("/api/reviews/" + base, s, java.util.Map.of("rating", 4, "comment", "Güzel oyun"));
        long other = fx.user();
        fx.purchase(other, Instant.now(), base);
        fx.sql("INSERT INTO review (user_id, base_game_id, rating, comment) VALUES (?, ?, 2, 'Fena değil')", other, base);

        JsonNode d = api.get("/api/catalog/products/" + base, s).body();
        assertThat(d.get("owned").asBoolean()).isTrue();
        assertThat(d.get("reviewCount").asInt()).isEqualTo(2);
        assertThat(d.get("avgRating").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(d.at("/myReview/rating").asInt()).isEqualTo(4);
        assertThat(d.get("recentReviews").toString()).contains("Yorumcu Ayşe").doesNotContain("@");

        JsonNode card = api.get("/api/catalog/products?q=Puanl%C4%B1%20Oyun&sort=rating_desc&size=100", s).body();
        assertThat(card.get("total").asLong()).isPositive();
    }

    @Test
    @DisplayName("Lookup listeleri: genre, publisher, developer")
    void lookups() {
        Fixtures.Catalog c = fx.catalog();
        assertThat(api.get("/api/catalog/genres", null).body().toString()).contains("\"id\":" + c.genreId());
        assertThat(api.get("/api/catalog/publishers", null).body().toString()).contains("\"id\":" + c.publisherId());
        assertThat(api.get("/api/catalog/developers", null).body().toString()).contains("\"id\":" + c.developerId());
    }
}
