package tr.edu.etu.oyunport.admin;

import org.springframework.stereotype.Service;
import tr.edu.etu.oyunport.common.ApiException;
import tr.edu.etu.oyunport.db.Db;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin rapor paneli (FR11). Raporlar app_admin havuzunda yalnızca rapor view'larından okunur
 * (app_admin'in sipariş/kütüphane tablolarına doğrudan SELECT yetkisi yoktur).
 * Rapor anahtarları beyaz listedir; SQL metnine kullanıcı girdisi eklenmez.
 */
@Service
public class ReportService {

    private final Db db;

    public ReportService(Db db) {
        this.db = db;
    }

    public record ReportDef(String key, String title, String description, String sql) {}

    public record ReportResult(String key, String title, String description, List<String> columns,
                               List<Map<String, Object>> rows, long elapsedMs) {}

    public record PlanResult(String key, String sql, List<String> plan, Double executionTimeMs, Double planningTimeMs) {}

    private static final Map<String, ReportDef> REPORTS = new LinkedHashMap<>();

    static {
        add("overview", "Genel bakış", "Aktif kullanıcı/ürün, sipariş durumları, gelir ve etkinlik sayıları.",
                "SELECT * FROM rpt_overview");
        add("sales-monthly", "Aylık satış", "v_game_sales_monthly view'ından ay bazında toplam adet ve gelir (son 24 ay).",
                """
                SELECT sales_month, sum(units_sold)::bigint AS units_sold, sum(revenue)::numeric(14,2) AS revenue,
                       count(DISTINCT product_id)::integer AS distinct_products
                  FROM v_game_sales_monthly
                 GROUP BY sales_month ORDER BY sales_month DESC LIMIT 24
                """);
        add("q1", "Q1 · Aylık en yüksek gelirli publisher'lar",
                "Her ay gelire göre ilk 3 publisher (DENSE_RANK) ve aylık gelir payı. Son 12 ay.",
                """
                SELECT * FROM rpt_q1_monthly_top_publishers
                 WHERE sales_month >= (SELECT max(sales_month) FROM rpt_q1_monthly_top_publishers) - interval '11 months'
                 ORDER BY sales_month DESC, revenue_rank, publisher_name
                """);
        add("q2", "Q2 · Genre içinde en çok satanlar",
                "Tamamlanmış siparişlerden tür başına en çok satan ilk 5 ürün (RANK, HAVING ≥ 25 satış).",
                "SELECT * FROM rpt_q2_genre_top_sellers ORDER BY genre_name, genre_rank, title");
        add("q3", "Q3 · Harcaması ortalamanın üzerindeki müşteriler",
                "Toplam harcaması müşteri ortalamasını aşanlar (ilk 100, harcamaya göre).",
                "SELECT * FROM rpt_q3_above_average_customers ORDER BY total_spent DESC, user_id LIMIT 100");
        add("q4", "Q4 · Satın alınıp az oynanan ana oyunlar",
                "Edinimden ≥ 30 gün geçmiş sahipler arasında toplam < 1 saat oynayanların oranı (ilk 50).",
                "SELECT * FROM rpt_q4_low_playtime_games ORDER BY low_play_pct DESC, eligible_owners DESC LIMIT 50");
        add("q5", "Q5 · Ana oyundan DLC'ye geçiş",
                "En az 100 sahibi olan ana oyunlarda DLC satın alma dönüşüm oranı (ilk 50).",
                "SELECT * FROM rpt_q5_dlc_conversion ORDER BY conversion_pct DESC, base_owners DESC LIMIT 50");
        add("user-activity", "Kullanıcı etkinliği",
                "Ay bazında aktif oyuncu, oturum ve oynama saati (son 24 ay).",
                "SELECT * FROM rpt_user_activity_monthly ORDER BY activity_month DESC LIMIT 24");
    }

    private static void add(String key, String title, String description, String sql) {
        REPORTS.put(key, new ReportDef(key, title, description, sql.strip()));
    }

    public List<ReportDef> definitions() {
        return List.copyOf(REPORTS.values());
    }

    public ReportResult run(long adminId, String key) {
        ReportDef def = def(key);
        return db.adminReadOnly(adminId, tx -> {
            long t0 = System.nanoTime();
            List<String> columns = new ArrayList<>();
            List<Map<String, Object>> rows = tx.query(def.sql(), rs -> {
                if (columns.isEmpty()) {
                    ResultSetMetaData md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i));
                }
                return toMap(rs);
            });
            if (columns.isEmpty()) {
                // boş sonuçta sütun adlarını yine de döndür
                try (var ps = tx.connection().prepareStatement(def.sql())) {
                    ResultSetMetaData md = ps.getMetaData();
                    if (md != null) for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i));
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new ReportResult(def.key(), def.title(), def.description(), columns, rows, ms);
        });
    }

    /** Canlı EXPLAIN (ANALYZE, BUFFERS) — yalnızca beyaz listedeki rapor sorguları için. */
    public PlanResult plan(long adminId, String key) {
        ReportDef def = def(key);
        return db.adminReadOnly(adminId, tx -> {
            List<String> lines = tx.query("EXPLAIN (ANALYZE, BUFFERS) " + def.sql(), rs -> rs.getString(1));
            Double exec = null, planning = null;
            for (String l : lines) {
                String t = l.trim();
                if (t.startsWith("Execution Time:")) exec = parseMs(t);
                if (t.startsWith("Planning Time:")) planning = parseMs(t);
            }
            return new PlanResult(def.key(), def.sql(), lines, exec, planning);
        });
    }

    private static Double parseMs(String line) {
        String num = line.replaceAll("[^0-9.]", "");
        return num.isEmpty() ? null : Double.parseDouble(num);
    }

    private static ReportDef def(String key) {
        ReportDef d = REPORTS.get(key);
        if (d == null) throw ApiException.notFound("Bilinmeyen rapor: " + key);
        return d;
    }

    private static Map<String, Object> toMap(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Date d) v = d.toLocalDate().toString();
            else if (v instanceof java.sql.Timestamp ts) v = ts.toInstant().toString();
            else if (v instanceof OffsetDateTime odt) v = odt.toInstant().toString();
            row.put(md.getColumnLabel(i), v);
        }
        return row;
    }
}
