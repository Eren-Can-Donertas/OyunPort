package tr.edu.etu.oyunport.profile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tr.edu.etu.oyunport.auth.AuthUser;
import tr.edu.etu.oyunport.common.ApiException;
import tr.edu.etu.oyunport.db.Db;
import tr.edu.etu.oyunport.db.Tx;

import java.time.Instant;
import java.util.Set;

/** Profil (FR01). app_customer yalnızca kendi app_user satırını görür/günceller (RLS + sütun yetkisi). */
@RestController
@RequestMapping("/api/me")
public class ProfileController {

    private final Db db;
    private final PasswordEncoder encoder;

    public ProfileController(Db db, PasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
    }

    public record Profile(long userId, String email, String displayName, Instant createdAt, boolean active,
                          Set<String> roles, long completedOrders, long ownedProducts) {}

    public record UpdateProfile(@NotBlank @Size(min = 2, max = 60) String displayName) {}

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 72) String newPassword) {}

    @GetMapping
    public Profile me(@AuthenticationPrincipal AuthUser user) {
        return db.customerReadOnly(user.userId(), tx -> tx.queryOne("""
                SELECT u.user_id, u.email, u.display_name, u.created_at, u.is_active,
                       (SELECT count(*) FROM store_order so WHERE so.user_id = u.user_id AND so.status = 'COMPLETED') AS orders,
                       (SELECT count(*) FROM library_entry le WHERE le.user_id = u.user_id) AS owned
                  FROM app_user u WHERE u.user_id = ?
                """, rs -> new Profile(rs.getLong("user_id"), rs.getString("email"), rs.getString("display_name"),
                Tx.getInstant(rs, "created_at"), rs.getBoolean("is_active"), user.roles(),
                rs.getLong("orders"), rs.getLong("owned")), user.userId()))
                .orElseThrow(() -> ApiException.notFound("Kullanıcı bulunamadı."));
    }

    @PutMapping
    public Profile update(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody UpdateProfile r) {
        db.customer(user.userId(), tx -> tx.update(
                "UPDATE app_user SET display_name = ? WHERE user_id = ?", r.displayName().trim(), user.userId()));
        return me(user);
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ChangePassword r) {
        db.customer(user.userId(), tx -> {
            String hash = tx.queryOne("SELECT password_hash FROM app_user WHERE user_id = ? FOR UPDATE",
                    rs -> rs.getString(1), user.userId()).orElseThrow(() -> ApiException.notFound("Kullanıcı bulunamadı."));
            if (!encoder.matches(r.currentPassword(), hash)) {
                throw ApiException.unprocessable("WRONG_PASSWORD", "Mevcut parola hatalı.");
            }
            return tx.update("UPDATE app_user SET password_hash = ? WHERE user_id = ?",
                    encoder.encode(r.newPassword()), user.userId());
        });
        return ResponseEntity.noContent().build();
    }
}
