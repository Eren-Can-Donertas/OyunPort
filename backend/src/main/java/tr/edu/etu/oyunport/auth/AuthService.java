package tr.edu.etu.oyunport.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import tr.edu.etu.oyunport.common.ApiException;
import tr.edu.etu.oyunport.db.Db;
import tr.edu.etu.oyunport.db.Tx;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Kayıt ve giriş (FR01). RLS altında giriş öncesi app_user okunamadığı için
 * SECURITY DEFINER servis fonksiyonları kullanılır: api_register_customer, api_login_lookup.
 * Parolalar BCrypt ile hash'lenir; düz parola veritabanına gitmez.
 */
@Service
public class AuthService {

    private final Db db;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(Db db, PasswordEncoder encoder, JwtService jwt) {
        this.db = db;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    public record AuthResult(String token, Instant expiresAt, UserView user) {}

    public record UserView(long userId, String email, String displayName, Set<String> roles) {}

    private record LoginRow(long userId, String email, String displayName, String passwordHash,
                            boolean active, Set<String> roles) {}

    public AuthResult register(String email, String displayName, String rawPassword) {
        String hash = encoder.encode(rawPassword);
        long userId = db.customer(null, tx -> tx.queryLong(
                "SELECT api_register_customer(?, ?, ?)", email, displayName, hash));
        LoginRow row = lookup(email).orElseThrow(() -> new IllegalStateException("Kayıt sonrası kullanıcı bulunamadı"));
        if (row.userId() != userId) {
            throw new IllegalStateException("Kayıt tutarsızlığı");
        }
        return issue(row);
    }

    public AuthResult login(String email, String rawPassword) {
        LoginRow row = lookup(email).orElse(null);
        // Kullanıcı yoksa da hash karşılaştırması yaparak zamanlama farkını azalt.
        if (row == null) {
            encoder.matches(rawPassword, "$2b$10$9eey.LjCrLLOX0vqIAGwq.Usgn7xjlGu9L1RgZRxvpCL37mo8sBpO");
            throw ApiException.unauthorized("E-posta veya parola hatalı.");
        }
        if (!encoder.matches(rawPassword, row.passwordHash())) {
            throw ApiException.unauthorized("E-posta veya parola hatalı.");
        }
        if (!row.active()) {
            throw ApiException.forbidden("Hesap pasif durumda (BR08).");
        }
        return issue(row);
    }

    private java.util.Optional<LoginRow> lookup(String email) {
        return db.customerReadOnly(null, tx -> tx.queryOne(
                "SELECT user_id, email, display_name, password_hash, is_active, roles FROM api_login_lookup(?)",
                rs -> new LoginRow(
                        rs.getLong("user_id"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("password_hash"),
                        rs.getBoolean("is_active"),
                        new LinkedHashSet<>(Tx.getStringList(rs, "roles"))),
                email));
    }

    private AuthResult issue(LoginRow row) {
        AuthUser user = new AuthUser(row.userId(), row.email(), row.displayName(), row.roles());
        JwtService.IssuedToken t = jwt.issue(user);
        return new AuthResult(t.token(), t.expiresAt(),
                new UserView(row.userId(), row.email(), row.displayName(), row.roles()));
    }
}
