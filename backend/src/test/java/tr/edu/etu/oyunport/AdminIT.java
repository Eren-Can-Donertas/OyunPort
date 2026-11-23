package tr.edu.etu.oyunport;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tr.edu.etu.oyunport.support.Api;
import tr.edu.etu.oyunport.support.IntegrationTestBase;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Admin katalog yönetimi (FR10) — app_admin havuzu üzerinden. */
class AdminIT extends IntegrationTestBase {

    private long ref(Api.Session admin, String kind, String name) {
        Api.Resp r = api.post("/api/admin/" + kind, admin, Map.of("name", name, "country", "TR"));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.body().get("id").asLong();
    }

    private Map<String, Object> product(String type, String title, String price, long publisher, List<Long> devs,
                                        List<Long> genres) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("description", "Admin panelinden eklendi");
        m.put("listPrice", price);
        m.put("releaseDate", "2026-05-01");
        m.put("publisherId", publisher);
        m.put("developerIds", devs);
        m.put("genreIds", genres);
        return m;
    }

    @Test
    @DisplayName("FR10: ana oyun ve DLC; publisher, developer, genre ile tek transaction'da oluşturulur ve katalogda görünür")
    void createBaseGameAndDlc() {
        Api.Session admin = seedAdmin();
        String u = uniq();
        long pub = ref(admin, "publishers", "Admin Yayın " + u);
        long dev1 = ref(admin, "developers", "Admin Stüdyo A " + u);
        long dev2 = ref(admin, "developers", "Admin Stüdyo B " + u);
        long genre = ref(admin, "genres", "Admin Türü " + u);

        Map<String, Object> base = product("BASE_GAME", "Admin Oyunu " + u, "349.90", pub, List.of(dev1, dev2), List.of(genre));
        base.put("minimumRequirements", "16 GB RAM, RTX 2060");
        Api.Resp b = api.post("/api/admin/products", admin, base);
        assertThat(b.status()).as(b.toString()).isEqualTo(201);
        long baseId = b.body().get("productId").asLong();
        assertThat(b.body().get("type").asText()).isEqualTo("BASE_GAME");
        assertThat(b.body().get("developerIds")).hasSize(2);

        Map<String, Object> dlc = product("DLC", "Admin DLC " + u, "99.90", pub, List.of(dev1), List.of(genre));
        dlc.put("baseGameId", baseId);
        dlc.put("contentSummary", "Yeni harita ve görevler");
        Api.Resp d = api.post("/api/admin/products", admin, dlc);
        assertThat(d.status()).as(d.toString()).isEqualTo(201);

        // Admin listesi tür filtresi (DLC formundaki ana oyun seçici için)
        Api.Resp onlyBase = api.get("/api/admin/products?type=BASE_GAME&q=" + u + "&size=50", admin);
        assertThat(onlyBase.body().get("total").asLong()).isEqualTo(1);
        assertThat(onlyBase.body().at("/items/0/productId").asLong()).isEqualTo(baseId);
        assertThat(api.get("/api/admin/products?type=DLC&q=" + u, admin).body().at("/items/0/type").asText()).isEqualTo("DLC");
        assertThat(api.get("/api/admin/products?type=XYZ", admin).status()).isEqualTo(400);

        JsonNode detail = api.get("/api/catalog/products/" + baseId, null).body();
        assertThat(detail.get("dlcs")).hasSize(1);
        assertThat(api.get("/api/catalog/products?q=Admin%20Oyunu%20" + u, null).body().get("total").asLong()).isEqualTo(1);
        assertThat(fx.scalarLong("SELECT count(*) FROM product_developer WHERE product_id = ?", baseId)).isEqualTo(2);
    }

    @Test
    @DisplayName("FR10: geçersiz ürün girişleri reddedilir; DLC yalnızca ana oyuna bağlanır; alt tür değiştirilemez")
    void invalidProductsRejected() {
        Api.Session admin = seedAdmin();
        String u = uniq();
        long pub = ref(admin, "publishers", "Geçersiz Yayın " + u);
        long dev = ref(admin, "developers", "Geçersiz Stüdyo " + u);
        long genre = ref(admin, "genres", "Geçersiz Tür " + u);

        Map<String, Object> noGenre = product("BASE_GAME", "Türsüz " + u, "10", pub, List.of(dev), List.of());
        noGenre.put("minimumRequirements", "x");
        assertThat(api.post("/api/admin/products", admin, noGenre).status()).isEqualTo(400);

        Map<String, Object> negative = product("BASE_GAME", "Negatif " + u, "-1", pub, List.of(dev), List.of(genre));
        negative.put("minimumRequirements", "x");
        assertThat(api.post("/api/admin/products", admin, negative).status()).isEqualTo(400);

        Map<String, Object> base = product("BASE_GAME", "Ana " + u, "10", pub, List.of(dev), List.of(genre));
        base.put("minimumRequirements", "x");
        long baseId = api.post("/api/admin/products", admin, base).body().get("productId").asLong();
        Map<String, Object> dlc = product("DLC", "DLC " + u, "5", pub, List.of(dev), List.of(genre));
        dlc.put("baseGameId", baseId);
        dlc.put("contentSummary", "y");
        long dlcId = api.post("/api/admin/products", admin, dlc).body().get("productId").asLong();

        Map<String, Object> dlcOfDlc = product("DLC", "DLC'nin DLC'si " + u, "5", pub, List.of(dev), List.of(genre));
        dlcOfDlc.put("baseGameId", dlcId);
        dlcOfDlc.put("contentSummary", "z");
        Api.Resp r = api.post("/api/admin/products", admin, dlcOfDlc);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.message()).contains("[BR05]");
        // Başarısız transaction'dan geriye ürün satırı kalmaz
        assertThat(fx.scalarLong("SELECT count(*) FROM product WHERE title = ?", "DLC'nin DLC'si " + u)).isZero();

        Map<String, Object> changeType = product("DLC", "Ana " + u, "10", pub, List.of(dev), List.of(genre));
        assertThat(api.put("/api/admin/products/" + baseId, admin, changeType).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("FR10 / BR08: fiyat ve tür güncellenir; ürün silinmez, pasife alınır ve katalogdan kalkar")
    void updateAndDeactivate() {
        Api.Session admin = seedAdmin();
        String u = uniq();
        long pub = ref(admin, "publishers", "Güncel Yayın " + u);
        long dev = ref(admin, "developers", "Güncel Stüdyo " + u);
        long g1 = ref(admin, "genres", "Güncel Tür 1 " + u);
        long g2 = ref(admin, "genres", "Güncel Tür 2 " + u);
        Map<String, Object> base = product("BASE_GAME", "Güncellenecek " + u, "100", pub, List.of(dev), List.of(g1));
        base.put("minimumRequirements", "x");
        long id = api.post("/api/admin/products", admin, base).body().get("productId").asLong();

        Map<String, Object> upd = product("BASE_GAME", "Güncellenmiş " + u, "79.99", pub, List.of(dev), List.of(g2));
        Api.Resp r = api.put("/api/admin/products/" + id, admin, upd);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        assertThat(r.body().get("listPrice").decimalValue()).isEqualByComparingTo("79.99");
        assertThat(r.body().get("genreIds").toString()).isEqualTo("[" + g2 + "]");

        Api.Resp off = api.put("/api/admin/products/" + id + "/active", admin, Map.of("active", false));
        assertThat(off.body().get("active").asBoolean()).isFalse();
        assertThat(api.get("/api/catalog/products?q=G%C3%BCncellenmi%C5%9F%20" + u, null).body().get("total").asLong()).isZero();
        assertThat(fx.scalarLong("SELECT count(*) FROM product WHERE product_id = ?", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("FR10: publisher/developer/genre CRUD; kullanılan kayıt silinemez (RESTRICT), tekrarlanan genre adı reddedilir")
    void referenceDataManagement() {
        Api.Session admin = seedAdmin();
        String u = uniq();
        long genre = ref(admin, "genres", "Ref Tür " + u);
        assertThat(api.post("/api/admin/genres", admin, Map.of("name", "Ref Tür " + u)).status()).isEqualTo(409);
        Api.Resp renamed = api.put("/api/admin/genres/" + genre, admin, Map.of("name", "Ref Tür Yeni " + u));
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.body().get("productCount").asLong()).isZero();

        long pub = ref(admin, "publishers", "Ref Yayın " + u);
        long dev = ref(admin, "developers", "Ref Stüdyo " + u);
        Map<String, Object> base = product("BASE_GAME", "Ref Oyun " + u, "10", pub, List.of(dev), List.of(genre));
        base.put("minimumRequirements", "x");
        assertThat(api.post("/api/admin/products", admin, base).status()).isEqualTo(201);

        assertThat(api.put("/api/admin/genres/" + genre, admin, Map.of("name", "Ref Tür Son " + u)).body()
                .get("productCount").asLong()).isEqualTo(1);
        assertThat(api.delete("/api/admin/genres/" + genre, admin).status()).isEqualTo(409);
        assertThat(api.delete("/api/admin/publishers/" + pub, admin).status()).isEqualTo(409);
        assertThat(api.delete("/api/admin/developers/" + dev, admin).status()).isEqualTo(409);

        long unused = ref(admin, "genres", "Kullanılmayan " + u);
        assertThat(api.delete("/api/admin/genres/" + unused, admin).status()).isEqualTo(204);
        assertThat(api.get("/api/admin/genres", admin).body().toString()).doesNotContain("Kullanılmayan " + u);
    }
}
