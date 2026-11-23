package tr.edu.etu.oyunport.auth;

import java.util.Set;

/** JWT'den çözülen oturum kullanıcısı (Spring Security principal). */
public record AuthUser(long userId, String email, String displayName, Set<String> roles) {

    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }

    public boolean isCustomer() {
        return roles.contains("CUSTOMER");
    }
}
