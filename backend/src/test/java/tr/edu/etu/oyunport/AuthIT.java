package tr.edu.etu.oyunport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tr.edu.etu.oyunport.support.Api;
import tr.edu.etu.oyunport.support.IntegrationTestBase;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Kayıt, giriş, profil ve rol bazlı erişim (FR01). */
class AuthIT extends IntegrationTestBase {

    @Test
    @DisplayName("Kayıt: CUSTOMER rolü atanır, parola BCrypt ile hash'lenir")
    void registerAssignsCustomerRoleAndHashesPassword() {
        String email = "Kayit-" + uniq() + "@Test.OyunPort";
        Api.Resp r = api.postAnon("/api/auth/register", Map.of("email", email, "displayName", "Yeni Üye", "password", "GucluParola1"));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("token").asText()).isNotBlank();
        assertThat(r.body().at("/user/roles").toString()).isEqualTo("[\"CUSTOMER\"]");
        assertThat(r.body().at("/user/email").asText()).isEqualTo(email.toLowerCase());
        long uid = r.body().at("/user/userId").asLong();
        String hash = fx.scalarString("SELECT password_hash FROM app_user WHERE user_id = ?", uid);
        assertThat(hash).startsWith("$2").doesNotContain("GucluParola1");
        assertThat(fx.column("SELECT r.role_name FROM user_role ur JOIN app_role r USING (role_id) WHERE ur.user_id = ?", uid))
                .containsExactly("CUSTOMER");
    }

    @Test
    @DisplayName("Kayıt: aynı e-posta (büyük/küçük harf farkı dahil) ikinci kez kaydedilemez -> 409")
    void duplicateEmailRejected() {
        String email = "dup-" + uniq() + "@test.oyunport";
        assertThat(api.postAnon("/api/auth/register", Map.of("email", email, "displayName", "Bir", "password", "Parola123!")).status()).isEqualTo(201);
        Api.Resp dup = api.postAnon("/api/auth/register", Map.of("email", email.toUpperCase(), "displayName", "İki", "password", "Parola123!"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.message()).contains("e-posta");
    }

    @Test
    @DisplayName("Kayıt: geçersiz e-posta / kısa parola -> 400")
    void invalidRegistrationRejected() {
        assertThat(api.postAnon("/api/auth/register", Map.of("email", "gecersiz", "displayName", "Ad", "password", "Parola123!")).status()).isEqualTo(400);
        assertThat(api.postAnon("/api/auth/register", Map.of("email", "a-" + uniq() + "@b.co", "displayName", "Ad", "password", "kisa")).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("Giriş: doğru parola 200; yanlış parola ve bilinmeyen e-posta 401; pasif hesap 403 (BR08)")
    void login() {
        Api.Session s = newCustomer();
        assertThat(api.postAnon("/api/auth/login", Map.of("email", s.email(), "password", "Parola123!")).status()).isEqualTo(200);
        assertThat(api.postAnon("/api/auth/login", Map.of("email", s.email(), "password", "YanlisParola")).status()).isEqualTo(401);
        assertThat(api.postAnon("/api/auth/login", Map.of("email", "yok-" + uniq() + "@x.yz", "password", "Parola123!")).status()).isEqualTo(401);
        fx.sql("UPDATE app_user SET is_active = false WHERE user_id = ?", s.userId());
        assertThat(api.postAnon("/api/auth/login", Map.of("email", s.email(), "password", "Parola123!")).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("Rol bazlı erişim: anonim 401, müşteri admin uç noktalarında 403, admin her iki role sahip")
    void roleBasedAccess() {
        Api.Session customer = newCustomer();
        Api.Session admin = seedAdmin();

        assertThat(api.get("/api/catalog/products", null).status()).isEqualTo(200);
        assertThat(api.get("/api/orders", null).status()).isEqualTo(401);
        assertThat(api.get("/api/library", null).status()).isEqualTo(401);
        assertThat(api.get("/api/admin/products", customer).status()).isEqualTo(403);
        assertThat(api.get("/api/admin/reports/run/overview", customer).status()).isEqualTo(403);
        assertThat(api.get("/api/admin/products", admin).status()).isEqualTo(200);
        assertThat(api.get("/api/library", admin).status()).isEqualTo(200);   // admin aynı zamanda CUSTOMER
        assertThat(api.get("/api/orders", customer).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Sahte/bozuk token reddedilir")
    void tamperedTokenRejected() {
        Api.Session s = newCustomer();
        Api.Session forged = new Api.Session(s.token().substring(0, s.token().length() - 4) + "abcd", s.userId(), s.email());
        assertThat(api.get("/api/orders", forged).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("Profil: görünen ad güncellenir; parola değişikliği mevcut parola doğrulamasıyla yapılır")
    void profileAndPassword() {
        Api.Session s = newCustomer("Eski Ad");
        Api.Resp me = api.get("/api/me", s);
        assertThat(me.body().get("displayName").asText()).isEqualTo("Eski Ad");
        Api.Resp upd = api.put("/api/me", s, Map.of("displayName", "Yeni Ad"));
        assertThat(upd.status()).isEqualTo(200);
        assertThat(upd.body().get("displayName").asText()).isEqualTo("Yeni Ad");

        assertThat(api.put("/api/me/password", s, Map.of("currentPassword", "yanlis", "newPassword", "YeniParola9")).status()).isEqualTo(422);
        assertThat(api.put("/api/me/password", s, Map.of("currentPassword", "Parola123!", "newPassword", "YeniParola9")).status()).isEqualTo(204);
        assertThat(api.postAnon("/api/auth/login", Map.of("email", s.email(), "password", "YeniParola9")).status()).isEqualTo(200);
        assertThat(api.postAnon("/api/auth/login", Map.of("email", s.email(), "password", "Parola123!")).status()).isEqualTo(401);
    }
}
