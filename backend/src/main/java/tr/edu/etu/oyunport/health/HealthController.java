package tr.edu.etu.oyunport.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tr.edu.etu.oyunport.db.Db;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sağlık kontrolü: her iki JDBC havuzu üzerinden gerçek PostgreSQL bağlantısını doğrular
 * ve bağlanılan veritabanı rolünü (current_user) gösterir.
 */
@RestController
public class HealthController {

    private final Db db;

    public HealthController(Db db) {
        this.db = db;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("customerPool", db.customerReadOnly(null, tx -> tx.queryOne(
                "SELECT current_user AS db_role, current_database() AS db, current_setting('server_version') AS version",
                rs -> Map.of("dbRole", rs.getString(1), "database", rs.getString(2), "serverVersion", rs.getString(3)))
                .orElseThrow()));
        out.put("adminPool", db.adminReadOnly(null, tx -> tx.queryOne(
                "SELECT current_user", rs -> Map.of("dbRole", rs.getString(1))).orElseThrow()));
        out.put("visibleCatalogProducts", db.customerReadOnly(null, tx -> tx.queryLong(
                "SELECT count(*) FROM product WHERE is_active")));
        return out;
    }
}
