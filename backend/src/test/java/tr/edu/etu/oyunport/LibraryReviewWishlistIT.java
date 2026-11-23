package tr.edu.etu.oyunport;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tr.edu.etu.oyunport.support.Api;
import tr.edu.etu.oyunport.support.Fixtures;
import tr.edu.etu.oyunport.support.IntegrationTestBase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kütüphane ve oynama oturumu (FR07), puan/yorum (FR08), istek listesi (FR09); UC03. */
class LibraryReviewWishlistIT extends IntegrationTestBase {

    private void buy(Api.Session s, long... productIds) {
        long orderId = api.post("/api/orders", s, Map.of("checkoutKey", UUID.randomUUID().toString(),
                "productIds", java.util.Arrays.stream(productIds).boxed().toList())).body().at("/order/orderId").asLong();
        Api.Resp r = api.post("/api/orders/" + orderId + "/payments", s,
                Map.of("requestKey", UUID.randomUUID().toString(), "simulateOutcome", "SUCCESS"));
        assertThat(r.body().get("orderStatus").asText()).isEqualTo("COMPLETED");
    }

    private JsonNode libraryItem(Api.Session s, long productId) {
        for (JsonNode n : api.get("/api/library", s).body()) {
            if (n.get("productId").asLong() == productId) return n;
        }
        throw new AssertionError("Kütüphanede yok: " + productId);
    }

    @Test
    @DisplayName("FR07 / UC03: ana oyun için oturum başlat/bitir ve dakika ile kayıt; toplam süre v_library_playtime'dan")
    void playSessions() {
        Fixtures.Catalog c = fx.catalog();
        long base = fx.baseGame(c, "Oturum Oyunu", "100");
        Api.Session s = newCustomer();
        buy(s, base);

        JsonNode item = libraryItem(s, base);
        long lib = item.get("libraryId").asLong();
        assertThat(item.get("totalSeconds").asLong()).isZero();
        assertThat(item.get("completedSessions").asInt()).isZero();

        Api.Resp start = api.post("/api/library/" + lib + "/sessions", s, null);
        assertThat(start.status()).isEqualTo(201);
        long sessionId = start.body().get("sessionId").asLong();
        assertThat(libraryItem(s, base).get("openSessionId").asLong()).isEqualTo(sessionId);
        Api.Resp second = api.post("/api/library/" + lib + "/sessions", s, null);
        assertThat(second.status()).isEqualTo(409);   // tek açık oturum

        Api.Resp end = api.post("/api/library/" + lib + "/sessions/" + sessionId + "/end", s, null);
        assertThat(end.status()).isEqualTo(200);
        assertThat(end.body().get("endedAt").isNull()).isFalse();
        assertThat(api.post("/api/library/" + lib + "/sessions/" + sessionId + "/end", s, null).status()).isEqualTo(409);

        // Ürün az önce edinildiği için 90 dakikalık kayıt edinim anına kırpılır; 1 dakikalık kayıt tam yazılır.
        assertThat(api.post("/api/library/" + lib + "/sessions/record", s, Map.of("minutes", 1)).status()).isEqualTo(201);
        JsonNode after = libraryItem(s, base);
        assertThat(after.get("completedSessions").asInt()).isEqualTo(2);
        assertThat(after.get("totalSeconds").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(api.get("/api/library/" + lib + "/sessions", s).body()).hasSize(2);
        assertThat(api.post("/api/library/" + lib + "/sessions/record", s, Map.of("minutes", 0)).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("UC03: DLC için oturum açılamaz; başka kullanıcının kütüphane kaydına erişilemez")
    void sessionRestrictions() {
        Fixtures.Catalog c = fx.catalog();
        long base = fx.baseGame(c, "Oturum Ana", "100");
        long dlc = fx.dlc(c, base, "Oturum DLC", "20");
        Api.Session s = newCustomer();
        Api.Session intruder = newCustomer();
        buy(s, base, dlc);

        long dlcLib = libraryItem(s, dlc).get("libraryId").asLong();
        Api.Resp r = api.post("/api/library/" + dlcLib + "/sessions", s, null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("SESSION_ONLY_FOR_BASE_GAME");

        long baseLib = libraryItem(s, base).get("libraryId").asLong();
        assertThat(api.post("/api/library/" + baseLib + "/sessions", intruder, null).status()).isEqualTo(404);
        assertThat(api.get("/api/library/" + baseLib + "/sessions", intruder).status()).isEqualTo(404);
        assertThat(fx.scalarLong("SELECT count(*) FROM play_session WHERE library_id = ?", baseLib)).isZero();
    }

    @Test
    @DisplayName("FR08: sahip olunan ana oyuna puan/yorum eklenir ve güncellenir (tek yorum)")
    void reviewUpsert() {
        Fixtures.Catalog c = fx.catalog();
        long base = fx.baseGame(c, "Yorum Oyunu", "100");
        Api.Session s = newCustomer();
        buy(s, base);

        Api.Resp created = api.put("/api/reviews/" + base, s, Map.of("rating", 5, "comment", "Muhteşem"));
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.body().get("created").asBoolean()).isTrue();
        Api.Resp updated = api.put("/api/reviews/" + base, s, Map.of("rating", 3, "comment", "Fikrim değişti"));
        assertThat(updated.body().get("created").asBoolean()).isFalse();
        assertThat(updated.body().get("rating").asInt()).isEqualTo(3);
        assertThat(fx.scalarLong("SELECT count(*) FROM review WHERE user_id = ? AND base_game_id = ?", s.userId(), base)).isEqualTo(1);
        assertThat(fx.scalarLong("SELECT count(*) FROM review WHERE user_id = ? AND updated_at > created_at", s.userId())).isEqualTo(1);
        assertThat(api.get("/api/reviews/mine", s).body()).hasSize(1);
    }

    @Test
    @DisplayName("UC03 / BR06: sahip olunmayan oyuna ve DLC'ye yorum reddedilir; puan aralığı API ve veritabanında denetlenir")
    void reviewRestrictions() throws SQLException {
        Fixtures.Catalog c = fx.catalog();
        long base = fx.baseGame(c, "Yorum Ana", "100");
        long dlc = fx.dlc(c, base, "Yorum DLC", "20");
        long notOwned = fx.baseGame(c, "Sahipsiz", "100");
        Api.Session s = newCustomer();
        buy(s, base, dlc);

        assertThat(api.put("/api/reviews/" + notOwned, s, Map.of("rating", 4)).status()).isEqualTo(403);
        Api.Resp onDlc = api.put("/api/reviews/" + dlc, s, Map.of("rating", 4));
        assertThat(onDlc.status()).isEqualTo(422);
        assertThat(onDlc.code()).isEqualTo("NOT_A_BASE_GAME");
        assertThat(api.put("/api/reviews/" + base, s, Map.of("rating", 6)).status()).isEqualTo(400);

        // Uygulama doğrulaması atlansa bile: app_customer rolüyle doğrudan SQL -> CHECK ihlali
        try (Connection conn = connect("app_customer", CUSTOMER_PW)) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.user_id', ?, true)")) {
                ps.setString(1, Long.toString(s.userId()));
                ps.execute();
            }
            assertThatThrownBy(() -> Fixtures.exec(conn, "INSERT INTO review (user_id, base_game_id, rating) VALUES (?, ?, 9)", s.userId(), base))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
            conn.rollback();
        }
        assertThat(fx.scalarLong("SELECT count(*) FROM review WHERE user_id = ?", s.userId())).isZero();
    }

    @Test
    @DisplayName("FR09: istek listesine ekleme (idempotent), listeleme ve çıkarma; pasif ürün eklenemez")
    void wishlist() {
        Fixtures.Catalog c = fx.catalog();
        long g1 = fx.baseGame(c, "İstek 1", "100");
        long g2 = fx.baseGame(c, "İstek 2", "100");
        long inactive = fx.baseGame(c, "İstek Pasif", "100");
        fx.sql("UPDATE product SET is_active = false WHERE product_id = ?", inactive);
        Api.Session s = newCustomer();

        assertThat(api.put("/api/wishlist/" + g1, s, null).status()).isEqualTo(204);
        assertThat(api.put("/api/wishlist/" + g1, s, null).status()).isEqualTo(204);
        assertThat(api.put("/api/wishlist/" + g2, s, null).status()).isEqualTo(204);
        assertThat(api.get("/api/wishlist", s).body()).hasSize(2);
        assertThat(api.get("/api/catalog/products/" + g1, s).body().get("wishlisted").asBoolean()).isTrue();

        assertThat(api.delete("/api/wishlist/" + g1, s).status()).isEqualTo(204);
        assertThat(api.delete("/api/wishlist/" + g1, s).status()).isEqualTo(404);
        assertThat(api.get("/api/wishlist", s).body()).hasSize(1);
        assertThat(api.put("/api/wishlist/" + inactive, s, null).status()).isEqualTo(422);
        assertThat(api.put("/api/wishlist/987654321", s, null).status()).isEqualTo(404);
    }
}
