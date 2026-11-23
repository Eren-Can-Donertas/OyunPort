package tr.edu.etu.oyunport;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tr.edu.etu.oyunport.support.Api;
import tr.edu.etu.oyunport.support.Fixtures;
import tr.edu.etu.oyunport.support.IntegrationTestBase;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analitik sorgular Q1–Q5 (FR11, rapor §8.3) ve §4.5 view'ları. Kontrollü fixture'larla
 * beklenen değerler elle hesaplanır ve view sonuçlarıyla karşılaştırılır.
 */
class ReportsIT extends IntegrationTestBase {

    @Test
    @DisplayName("Q1: ay içinde publisher gelir sıralaması (DENSE_RANK) ve gelir payı doğru hesaplanır")
    void q1MonthlyTopPublishers() {
        Fixtures.Catalog p = fx.catalog();
        Fixtures.Catalog q = fx.catalog();
        long big = fx.baseGame(p, "Q1 Büyük", "5000.00");
        long small = fx.baseGame(q, "Q1 Küçük", "100.00");
        Instant feb = Instant.parse("2024-02-10T12:00:00Z");
        fx.purchase(fx.user(), feb, big);
        fx.purchase(fx.user(), feb, big);
        fx.purchase(fx.user(), feb, small);
        // Başarısız/pending sipariş gelire katılmaz
        long pendingOrder = fx.pendingOrder(fx.user(), feb, big);
        fx.sql("INSERT INTO payment_attempt (order_id, amount, status, request_key) VALUES (?, 5000.00, 'FAILED', gen_random_uuid())", pendingOrder);

        List<String> rows = fx.column("""
                SELECT revenue_rank || '|' || publisher_id || '|' || revenue || '|' || month_revenue || '|' || revenue_share_pct
                  FROM rpt_q1_monthly_top_publishers WHERE sales_month = DATE '2024-02-01' ORDER BY revenue_rank""");
        assertThat(rows).containsExactly(
                "1|" + p.publisherId() + "|10000.00|10100.00|99.01",
                "2|" + q.publisherId() + "|100.00|10100.00|0.99");
    }

    @Test
    @DisplayName("Q2: tür içinde satış sıralaması (RANK), HAVING eşiği (>= 25) ve yalnızca tamamlanmış siparişler")
    void q2GenreTopSellers() {
        Fixtures.Catalog c = fx.catalog();
        long x = fx.baseGame(c, "Q2 X", "10.00");
        long y = fx.baseGame(c, "Q2 Y", "10.00");
        long z = fx.baseGame(c, "Q2 Z", "10.00");
        Instant t = Instant.parse("2024-05-05T10:00:00Z");
        for (int i = 0; i < 30; i++) fx.purchase(fx.user(), t, y);
        for (int i = 0; i < 25; i++) fx.purchase(fx.user(), t, x);
        for (int i = 0; i < 10; i++) fx.purchase(fx.user(), t, z);

        List<String> rows = fx.column("""
                SELECT genre_rank || '|' || product_id || '|' || units_sold || '|' || revenue
                  FROM rpt_q2_genre_top_sellers WHERE genre_id = ? ORDER BY genre_rank""", c.genreId());
        assertThat(rows).containsExactly("1|" + y + "|30|300.00", "2|" + x + "|25|250.00");
        assertThat(fx.scalarLong("SELECT units_sold FROM v_game_sales_monthly WHERE product_id = ? AND sales_month = DATE '2024-05-01'", y)).isEqualTo(30);
    }

    @Test
    @DisplayName("Q3: harcaması müşteri ortalamasını aşan kullanıcı listelenir; ortalamanın altındaki listelenmez")
    void q3AboveAverageCustomers() {
        Fixtures.Catalog c = fx.catalog();
        long luxury = fx.baseGame(c, "Q3 Lüks", "99999.99");
        long cheap = fx.baseGame(c, "Q3 Ucuz", "0.01");
        long whale = fx.user();
        long minnow = fx.user();
        fx.purchase(whale, Instant.now(), luxury);
        fx.purchase(minnow, Instant.now(), cheap);

        assertThat(fx.scalarLong("SELECT count(*) FROM rpt_q3_above_average_customers WHERE user_id = ?", whale)).isEqualTo(1);
        assertThat(fx.scalarLong("SELECT count(*) FROM rpt_q3_above_average_customers WHERE user_id = ?", minnow)).isZero();
        assertThat(fx.scalarString("SELECT total_spent FROM rpt_q3_above_average_customers WHERE user_id = ?", whale)).isEqualTo("99999.99");
        assertThat(fx.scalarLong("SELECT count(*) FROM rpt_q3_above_average_customers WHERE user_id = ? AND spend_ratio > 1 AND completed_orders = 1", whale)).isEqualTo(1);
    }

    @Test
    @DisplayName("Q4: edinimden >= 30 gün geçmiş sahiplerde toplam < 1 saat oynayanların oranı (oturumsuzlar dahil)")
    void q4LowPlaytimeGames() {
        Fixtures.Catalog c = fx.catalog();
        long game = fx.baseGame(c, "Q4 Oyun", "100.00");
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        for (int i = 0; i < 20; i++) {
            long u = fx.user();
            fx.purchase(u, old, game);
            long lib = fx.libraryId(u, game);
            if (i < 5) continue;                                         // 5 sahip: hiç oturum yok
            if (i < 15) fx.session(lib, old.plus(1, ChronoUnit.DAYS), 30);   // 10 sahip: 30 dk
            else fx.session(lib, old.plus(1, ChronoUnit.DAYS), 120);         // 5 sahip: 2 saat
        }
        for (int i = 0; i < 3; i++) fx.purchase(fx.user(), Instant.now().minus(5, ChronoUnit.DAYS), game);   // uygun değil

        String row = fx.scalarString("""
                SELECT eligible_owners || '|' || low_play_owners || '|' || low_play_pct || '|' || avg_hours_played
                  FROM rpt_q4_low_playtime_games WHERE product_id = ?""", game);
        // ortalama = (5*0 + 10*0.5 + 5*2) / 20 = 0.75 saat
        assertThat(row).isEqualTo("20|15|75.00|0.75");
    }

    @Test
    @DisplayName("Q5: ana oyun sahiplerinde DLC dönüşüm oranı (EXISTS), en az 100 sahip, pasif kullanıcı hariç")
    void q5DlcConversion() {
        Fixtures.Catalog c = fx.catalog();
        long base = fx.baseGame(c, "Q5 Ana", "100.00");
        long dlc = fx.dlc(c, base, "Q5 DLC", "20.00");
        long lonely = fx.baseGame(c, "Q5 Az Sahipli", "100.00");
        fx.dlc(c, lonely, "Q5 Az Sahipli DLC", "20.00");
        Instant t = Instant.now().minus(10, ChronoUnit.DAYS);
        for (int i = 0; i < 100; i++) {
            long u = fx.user();
            if (i < 40) fx.purchase(u, t, base, dlc);
            else fx.purchase(u, t, base);
        }
        long inactive = fx.user();
        fx.purchase(inactive, t, base, dlc);
        fx.sql("UPDATE app_user SET is_active = false WHERE user_id = ?", inactive);
        for (int i = 0; i < 5; i++) fx.purchase(fx.user(), t, lonely);

        assertThat(fx.scalarString("""
                SELECT dlc_count || '|' || base_owners || '|' || dlc_buyers || '|' || conversion_pct
                  FROM rpt_q5_dlc_conversion WHERE base_game_id = ?""", base)).isEqualTo("1|100|40|40.00");
        assertThat(fx.scalarLong("SELECT count(*) FROM rpt_q5_dlc_conversion WHERE base_game_id = ?", lonely)).isZero();
    }

    @Test
    @DisplayName("§4.5 view'ları: v_order_summary toplamı kalemlerden; v_library_playtime oturumsuz kayıtları da listeler")
    void plannedViews() {
        Fixtures.Catalog c = fx.catalog();
        long a = fx.baseGame(c, "View A", "12.50");
        long b = fx.baseGame(c, "View B", "7.25");
        long u = fx.user();
        long order = fx.purchase(u, Instant.now().minus(1, ChronoUnit.DAYS), a, b);
        fx.session(fx.libraryId(u, a), Instant.now().minus(1, ChronoUnit.HOURS), 45);

        assertThat(fx.scalarString("SELECT item_count || '|' || order_total FROM v_order_summary WHERE order_id = ?", order)).isEqualTo("2|19.75");
        assertThat(fx.column("SELECT product_id || '|' || completed_sessions || '|' || total_seconds FROM v_library_playtime WHERE user_id = ? ORDER BY product_id", u))
                .containsExactly(a + "|1|2700", b + "|0|0");
    }

    @Test
    @DisplayName("FR11: admin rapor API'si tüm raporları çalıştırır ve canlı EXPLAIN (ANALYZE, BUFFERS) planı döndürür")
    void adminReportApi() {
        Api.Session admin = seedAdmin();
        JsonNode defs = api.get("/api/admin/reports/definitions", admin).body();
        assertThat(defs).hasSizeGreaterThanOrEqualTo(8);
        for (JsonNode d : defs) {
            String key = d.get("key").asText();
            Api.Resp r = api.get("/api/admin/reports/run/" + key, admin);
            assertThat(r.status()).as(key + " " + r).isEqualTo(200);
            assertThat(r.body().get("columns").size()).as(key).isPositive();
        }
        Api.Resp plan = api.get("/api/admin/reports/plan/q5", admin);
        assertThat(plan.status()).isEqualTo(200);
        assertThat(plan.body().get("plan").toString()).contains("Buffers").contains("Execution Time");
        assertThat(plan.body().get("executionTimeMs").isNumber()).isTrue();
        assertThat(api.get("/api/admin/reports/run/drop_table", admin).status()).isEqualTo(404);
    }
}
