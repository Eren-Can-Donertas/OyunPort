-- =====================================================================
-- OyunPort  V3: View'lar (rapor §4.5) ve analitik sorgular Q1–Q5 (rapor §8.3)
--
-- Güvenlik notu (Tasarım notları Ç5):
--   * Müşterinin kendi verisini okuduğu view'lar security_invoker = true:
--     sorgulayan rolün RLS politikaları uygulanır.
--   * Rapor view'ları (v_game_sales_monthly, rpt_*) sahibinin (db_admin)
--     yetkisiyle çalışır; yalnızca app_admin ve reporting_readonly'ye açılır.
-- Gelir yalnızca COMPLETED siparişlerin kalemlerinden hesaplanır;
-- PAYMENT_ATTEMPT ile JOIN yapılmaz (satış tutarı çift sayılmaz).
-- Aylık gruplama Europe/Istanbul saat dilimine göredir.
-- =====================================================================

-- ---------------------------------------------------------------------
-- §4.5 Planlanan view'lar
-- ---------------------------------------------------------------------
CREATE VIEW v_order_summary WITH (security_invoker = true) AS
SELECT so.order_id,
       so.user_id,
       u.email,
       u.display_name,
       so.status,
       so.created_at,
       so.completed_at,
       so.checkout_key,
       count(oi.line_no)::integer                         AS item_count,
       coalesce(sum(oi.unit_price_paid), 0)::numeric(12,2) AS order_total
  FROM store_order so
  JOIN app_user u        ON u.user_id = so.user_id
  LEFT JOIN order_item oi ON oi.order_id = so.order_id
 GROUP BY so.order_id, so.user_id, u.email, u.display_name, so.status,
          so.created_at, so.completed_at, so.checkout_key;

CREATE VIEW v_game_sales_monthly AS
SELECT date_trunc('month', so.completed_at AT TIME ZONE 'Europe/Istanbul')::date AS sales_month,
       p.product_id,
       p.title,
       CASE WHEN bg.product_id IS NOT NULL THEN 'BASE_GAME' ELSE 'DLC' END       AS product_type,
       count(*)::integer                                                       AS units_sold,
       sum(oi.unit_price_paid)::numeric(14,2)                                  AS revenue
  FROM store_order so
  JOIN order_item oi     ON oi.order_id = so.order_id
  JOIN product p         ON p.product_id = oi.product_id
  LEFT JOIN base_game bg ON bg.product_id = p.product_id
 WHERE so.status = 'COMPLETED'
 GROUP BY 1, p.product_id, p.title, bg.product_id;

-- Oturumsuz ürünler de listelenir (LEFT JOIN); yalnızca tamamlanmış oturumlar toplanır.
CREATE VIEW v_library_playtime WITH (security_invoker = true) AS
SELECT le.library_id,
       le.user_id,
       le.product_id,
       le.acquired_at,
       count(ps.session_id) FILTER (WHERE ps.ended_at IS NOT NULL)::integer AS completed_sessions,
       coalesce(sum(extract(epoch FROM ps.ended_at - ps.started_at))
                FILTER (WHERE ps.ended_at IS NOT NULL), 0)::bigint          AS total_seconds,
       max(ps.started_at)                                                  AS last_played_at,
       bool_or(ps.session_id IS NOT NULL AND ps.ended_at IS NULL)          AS has_open_session
  FROM library_entry le
  LEFT JOIN play_session ps ON ps.library_id = le.library_id
 GROUP BY le.library_id, le.user_id, le.product_id, le.acquired_at;

-- ---------------------------------------------------------------------
-- Katalog için herkese açık, e-posta içermeyen view'lar
-- (review tablosu RLS ile korunur; müşteri yalnızca kendi yorum satırına erişir)
-- ---------------------------------------------------------------------
CREATE VIEW v_product_rating AS
SELECT r.base_game_id                        AS product_id,
       count(*)::integer                     AS review_count,
       round(avg(r.rating)::numeric, 2)      AS avg_rating
  FROM review r
 GROUP BY r.base_game_id;

CREATE VIEW v_public_review AS
SELECT r.base_game_id,
       u.display_name,
       r.rating,
       r.comment,
       r.created_at,
       r.updated_at
  FROM review r
  JOIN app_user u ON u.user_id = r.user_id;

-- ---------------------------------------------------------------------
-- Admin rapor paneli yardımcı view'ları
-- ---------------------------------------------------------------------
CREATE VIEW rpt_overview AS
SELECT (SELECT count(*) FROM app_user WHERE is_active)                        AS active_users,
       (SELECT count(*) FROM product WHERE is_active)                         AS active_products,
       (SELECT count(*) FROM base_game)                                       AS base_games,
       (SELECT count(*) FROM dlc)                                             AS dlcs,
       (SELECT count(*) FROM store_order WHERE status = 'COMPLETED')          AS completed_orders,
       (SELECT count(*) FROM store_order WHERE status = 'PENDING')            AS pending_orders,
       (SELECT count(*) FROM store_order WHERE status = 'CANCELLED')          AS cancelled_orders,
       (SELECT coalesce(sum(oi.unit_price_paid), 0)
          FROM order_item oi JOIN store_order so ON so.order_id = oi.order_id
         WHERE so.status = 'COMPLETED')                                       AS total_revenue,
       (SELECT count(*) FROM payment_attempt WHERE status = 'FAILED')         AS failed_payments,
       (SELECT count(*) FROM library_entry)                                   AS library_entries,
       (SELECT count(*) FROM play_session)                                    AS play_sessions,
       (SELECT count(*) FROM review)                                          AS reviews;

-- Kullanıcı etkinliği: ay bazında aktif oyuncu, oturum ve oynama saati.
CREATE VIEW rpt_user_activity_monthly AS
SELECT date_trunc('month', ps.started_at AT TIME ZONE 'Europe/Istanbul')::date      AS activity_month,
       count(DISTINCT le.user_id)::integer                                          AS active_players,
       count(*)::integer                                                            AS sessions,
       round((sum(extract(epoch FROM ps.ended_at - ps.started_at)) / 3600.0)::numeric, 1) AS hours_played
  FROM play_session ps
  JOIN library_entry le ON le.library_id = ps.library_id
 WHERE ps.ended_at IS NOT NULL
 GROUP BY 1;

-- =====================================================================
-- Q1  Aylık en yüksek gelirli publisher'lar
--     STORE_ORDER + ORDER_ITEM + PRODUCT + PUBLISHER; ay/publisher GROUP BY;
--     aylık toplam alt sorgusu; DENSE_RANK ile her ay ilk 3 ve gelir payı.
-- =====================================================================
CREATE VIEW rpt_q1_monthly_top_publishers AS
WITH monthly_publisher AS (
    SELECT date_trunc('month', so.completed_at AT TIME ZONE 'Europe/Istanbul')::date AS sales_month,
           pu.publisher_id,
           pu.name                     AS publisher_name,
           count(*)                    AS units_sold,
           sum(oi.unit_price_paid)     AS revenue
      FROM store_order so
      JOIN order_item oi ON oi.order_id = so.order_id
      JOIN product p     ON p.product_id = oi.product_id
      JOIN publisher pu  ON pu.publisher_id = p.publisher_id
     WHERE so.status = 'COMPLETED'
     GROUP BY 1, pu.publisher_id, pu.name
),
ranked AS (
    SELECT mp.*,
           mt.month_revenue,
           DENSE_RANK() OVER (PARTITION BY mp.sales_month ORDER BY mp.revenue DESC) AS revenue_rank
      FROM monthly_publisher mp
      JOIN (SELECT sales_month, sum(revenue) AS month_revenue      -- aylık toplam alt sorgusu
              FROM monthly_publisher
             GROUP BY sales_month) mt ON mt.sales_month = mp.sales_month
)
SELECT sales_month,
       revenue_rank::integer                                        AS revenue_rank,
       publisher_id,
       publisher_name,
       units_sold::integer                                          AS units_sold,
       revenue::numeric(14,2)                                       AS revenue,
       month_revenue::numeric(14,2)                                 AS month_revenue,
       round(100.0 * revenue / nullif(month_revenue, 0), 2)         AS revenue_share_pct
  FROM ranked
 WHERE revenue_rank <= 3;

-- =====================================================================
-- Q2  Genre içinde en çok satan ürünler
--     STORE_ORDER + ORDER_ITEM + PRODUCT + PRODUCT_GENRE + GENRE;
--     tamamlanmış sipariş alt sorgusu; genre/product GROUP BY; HAVING eşiği (>= 25 satış);
--     genre başına RANK (ilk 5). Çok türlü ürün her türde ayrı sayılır,
--     genre toplamları birbirine eklenmez.
-- =====================================================================
CREATE VIEW rpt_q2_genre_top_sellers AS
WITH genre_product_sales AS (
    SELECT g.genre_id,
           g.name                   AS genre_name,
           p.product_id,
           p.title,
           count(*)                 AS units_sold,
           sum(oi.unit_price_paid)  AS revenue
      FROM order_item oi
      JOIN product p        ON p.product_id = oi.product_id
      JOIN product_genre pg ON pg.product_id = p.product_id
      JOIN genre g          ON g.genre_id = pg.genre_id
     WHERE oi.order_id IN (SELECT so.order_id FROM store_order so WHERE so.status = 'COMPLETED')
     GROUP BY g.genre_id, g.name, p.product_id, p.title
    HAVING count(*) >= 25
),
ranked AS (
    SELECT gps.*,
           RANK() OVER (PARTITION BY gps.genre_id ORDER BY gps.units_sold DESC) AS genre_rank
      FROM genre_product_sales gps
)
SELECT genre_id,
       genre_name,
       genre_rank::integer          AS genre_rank,
       product_id,
       title,
       units_sold::integer          AS units_sold,
       revenue::numeric(14,2)       AS revenue
  FROM ranked
 WHERE genre_rank <= 5;

-- =====================================================================
-- Q3  Harcaması ortalamanın üzerindeki müşteriler
--     APP_USER + STORE_ORDER + ORDER_ITEM; kullanıcı bazında GROUP BY/HAVING;
--     kullanıcı toplamlarının ortalamasını hesaplayan alt sorgu.
-- =====================================================================
CREATE VIEW rpt_q3_above_average_customers AS
WITH avg_spend AS (
    SELECT avg(t.total_spent) AS avg_customer_spend
      FROM (SELECT so2.user_id, sum(oi2.unit_price_paid) AS total_spent
              FROM store_order so2
              JOIN order_item oi2 ON oi2.order_id = so2.order_id
             WHERE so2.status = 'COMPLETED'
             GROUP BY so2.user_id) t
)
SELECT u.user_id,
       u.email,
       u.display_name,
       count(DISTINCT so.order_id)::integer                          AS completed_orders,
       sum(oi.unit_price_paid)::numeric(14,2)                         AS total_spent,
       round((SELECT avg_customer_spend FROM avg_spend), 2)           AS avg_customer_spend,
       round(sum(oi.unit_price_paid) / (SELECT avg_customer_spend FROM avg_spend), 2) AS spend_ratio
  FROM app_user u
  JOIN store_order so ON so.user_id = u.user_id AND so.status = 'COMPLETED'
  JOIN order_item oi  ON oi.order_id = so.order_id
 GROUP BY u.user_id, u.email, u.display_name
HAVING sum(oi.unit_price_paid) > (SELECT avg_customer_spend FROM avg_spend);

-- =====================================================================
-- Q4  Satın alınıp az oynanan ana oyunlar
--     LIBRARY_ENTRY + BASE_GAME + PRODUCT + PLAY_SESSION; oturumlar LEFT JOIN;
--     kullanıcı/oyun sürelerini toplayan alt sorgu; oyun bazında GROUP BY/HAVING;
--     edinimden en az 30 gün geçmiş sahipler arasında toplam < 1 saat oynayanların oranı.
-- =====================================================================
CREATE VIEW rpt_q4_low_playtime_games AS
SELECT p.product_id,
       p.title,
       count(*)::integer                                                       AS eligible_owners,
       count(*) FILTER (WHERE op.played_seconds < 3600)::integer               AS low_play_owners,
       round(100.0 * count(*) FILTER (WHERE op.played_seconds < 3600) / count(*), 2) AS low_play_pct,
       round((avg(op.played_seconds) / 3600.0)::numeric, 2)                    AS avg_hours_played
  FROM (SELECT le.library_id,
               le.product_id,
               coalesce(sum(extract(epoch FROM ps.ended_at - ps.started_at))
                        FILTER (WHERE ps.ended_at IS NOT NULL), 0) AS played_seconds
          FROM library_entry le
          JOIN base_game bg          ON bg.product_id = le.product_id
          LEFT JOIN play_session ps  ON ps.library_id = le.library_id
         WHERE le.acquired_at <= now() - interval '30 days'
         GROUP BY le.library_id, le.product_id) op
  JOIN product p ON p.product_id = op.product_id
 GROUP BY p.product_id, p.title
HAVING count(*) >= 20;

-- =====================================================================
-- Q5  Ana oyundan DLC satın almaya geçiş
--     APP_USER + LIBRARY_ENTRY + BASE_GAME + DLC + PRODUCT; ana oyun sahipleri için
--     DLC sahipliği EXISTS alt sorgusu; oyun bazında GROUP BY; en az 100 sahip (HAVING);
--     dönüşüm oranı. Yalnızca DLC'si olan ana oyunlar ve aktif kullanıcılar.
-- =====================================================================
CREATE VIEW rpt_q5_dlc_conversion AS
SELECT o.base_game_id,
       p.title,
       (SELECT count(*) FROM dlc d0 WHERE d0.base_game_id = o.base_game_id)::integer AS dlc_count,
       count(*)::integer                                                AS base_owners,
       count(*) FILTER (WHERE o.bought_dlc)::integer                    AS dlc_buyers,
       round(100.0 * count(*) FILTER (WHERE o.bought_dlc) / count(*), 2) AS conversion_pct
  FROM (SELECT le.user_id,
               bg.product_id AS base_game_id,
               EXISTS (SELECT 1                                   -- DLC sahipliği EXISTS alt sorgusu
                         FROM library_entry le2
                         JOIN dlc d ON d.product_id = le2.product_id
                        WHERE le2.user_id = le.user_id
                          AND d.base_game_id = bg.product_id) AS bought_dlc
          FROM library_entry le
          JOIN app_user u   ON u.user_id = le.user_id AND u.is_active
          JOIN base_game bg ON bg.product_id = le.product_id
         WHERE EXISTS (SELECT 1 FROM dlc d1 WHERE d1.base_game_id = bg.product_id)) o
  JOIN product p ON p.product_id = o.base_game_id
 GROUP BY o.base_game_id, p.title
HAVING count(*) >= 100;
