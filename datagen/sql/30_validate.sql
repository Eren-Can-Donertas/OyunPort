-- Çalıştıran rol: db_admin
-- Yükleme sonrası ilişkisel denetimler (rapor §8.1). İhlal sayılarının tamamı 0 olmalıdır.
\set ON_ERROR_STOP on
\pset footer off

\echo '== Satır sayıları ve rapor §8.1 hedefleri'
SELECT tablo, satir, hedef FROM (VALUES
    (1,  'app_user',        (SELECT count(*) FROM app_user),        '40.000'),
    (2,  'product',         (SELECT count(*) FROM product),         '5.000 (≈4.000 ana oyun / 1.000 DLC)'),
    (3,  'base_game',       (SELECT count(*) FROM base_game),       '≈4.000'),
    (4,  'dlc',             (SELECT count(*) FROM dlc),             '≈1.000'),
    (5,  'store_order',     (SELECT count(*) FROM store_order),     '300.000 (≈270.000 tamamlanmış)'),
    (6,  '  COMPLETED',     (SELECT count(*) FROM store_order WHERE status = 'COMPLETED'), '≈270.000'),
    (7,  '  PENDING',       (SELECT count(*) FROM store_order WHERE status = 'PENDING'),   ''),
    (8,  '  CANCELLED',     (SELECT count(*) FROM store_order WHERE status = 'CANCELLED'), ''),
    (9,  'order_item',      (SELECT count(*) FROM order_item),      '≈600.000'),
    (10, 'payment_attempt', (SELECT count(*) FROM payment_attempt), '>= 300.000'),
    (11, '  FAILED',        (SELECT count(*) FROM payment_attempt WHERE status = 'FAILED'), ''),
    (12, 'library_entry',   (SELECT count(*) FROM library_entry),   '≈540.000'),
    (13, 'play_session',    (SELECT count(*) FROM play_session),    '1.000.000'),
    (14, 'review',          (SELECT count(*) FROM review),          '80.000'),
    (15, 'wishlist',        (SELECT count(*) FROM wishlist),        '150.000'),
    (16, 'publisher',       (SELECT count(*) FROM publisher),       ''),
    (17, 'developer',       (SELECT count(*) FROM developer),       ''),
    (18, 'genre',           (SELECT count(*) FROM genre),           ''),
    (19, 'product_genre',   (SELECT count(*) FROM product_genre),   ''),
    (20, 'product_developer', (SELECT count(*) FROM product_developer), ''),
    (21, 'user_role',       (SELECT count(*) FROM user_role),       '')
) AS t(sira, tablo, satir, hedef) ORDER BY sira;

\echo '== İş kuralı denetimleri (ihlal sayısı = 0 beklenir)'
SELECT kural, ihlal, CASE WHEN ihlal = 0 THEN 'OK' ELSE 'HATA' END AS sonuc FROM (VALUES
 ('BR01 alt türü tam olarak bir olmayan ürün',
   (SELECT count(*) FROM product p WHERE (SELECT count(*) FROM base_game b WHERE b.product_id = p.product_id)
                                       + (SELECT count(*) FROM dlc d WHERE d.product_id = p.product_id) <> 1)),
 ('§4.2 genre veya developer bağlantısı olmayan ürün',
   (SELECT count(*) FROM product p WHERE NOT EXISTS (SELECT 1 FROM product_genre g WHERE g.product_id = p.product_id)
                                      OR NOT EXISTS (SELECT 1 FROM product_developer d WHERE d.product_id = p.product_id))),
 ('BR04 kalemsiz sipariş',
   (SELECT count(*) FROM store_order so WHERE NOT EXISTS (SELECT 1 FROM order_item oi WHERE oi.order_id = so.order_id))),
 ('BR09 COMPLETED ama SUCCESS ödemesi tam olarak bir değil',
   (SELECT count(*) FROM store_order so WHERE so.status = 'COMPLETED'
       AND (SELECT count(*) FROM payment_attempt pa WHERE pa.order_id = so.order_id AND pa.status = 'SUCCESS') <> 1)),
 ('BR09 COMPLETED olmayan siparişte SUCCESS ödeme',
   (SELECT count(*) FROM payment_attempt pa JOIN store_order so USING (order_id)
     WHERE pa.status = 'SUCCESS' AND so.status <> 'COMPLETED')),
 ('BR04 ödeme tutarı kalem toplamından farklı',
   (SELECT count(*) FROM payment_attempt pa
     WHERE pa.amount <> (SELECT sum(oi.unit_price_paid) FROM order_item oi WHERE oi.order_id = pa.order_id))),
 ('BR04 tamamlanmış kalem olup kütüphane kaydı olmayan',
   (SELECT count(*) FROM order_item oi JOIN store_order so USING (order_id)
     WHERE so.status = 'COMPLETED'
       AND NOT EXISTS (SELECT 1 FROM library_entry le WHERE le.order_id = oi.order_id AND le.line_no = oi.line_no))),
 ('BR04 kütüphane kaydı tamamlanmamış siparişten',
   (SELECT count(*) FROM library_entry le JOIN store_order so USING (order_id) WHERE so.status <> 'COMPLETED')),
 ('BR04 kütüphane kullanıcı/ürün kaynak kalemle tutarsız',
   (SELECT count(*) FROM library_entry le
      JOIN order_item oi ON oi.order_id = le.order_id AND oi.line_no = le.line_no
      JOIN store_order so ON so.order_id = le.order_id
     WHERE oi.product_id <> le.product_id OR so.user_id <> le.user_id)),
 ('BR02 aynı kullanıcı-ürün için birden fazla sahiplik',
   (SELECT count(*) FROM (SELECT user_id, product_id FROM library_entry GROUP BY 1, 2 HAVING count(*) > 1) x)),
 ('BR05 ana oyunu olmadan DLC sahipliği',
   (SELECT count(*) FROM library_entry le JOIN dlc d ON d.product_id = le.product_id
     WHERE NOT EXISTS (SELECT 1 FROM library_entry b WHERE b.user_id = le.user_id AND b.product_id = d.base_game_id))),
 ('BR06 DLC üzerinde oynama oturumu',
   (SELECT count(*) FROM play_session ps JOIN library_entry le USING (library_id)
     WHERE NOT EXISTS (SELECT 1 FROM base_game bg WHERE bg.product_id = le.product_id))),
 ('BR06 edinimden önce başlayan oturum',
   (SELECT count(*) FROM play_session ps JOIN library_entry le USING (library_id) WHERE ps.started_at < le.acquired_at)),
 ('§5 ended_at < started_at',
   (SELECT count(*) FROM play_session WHERE ended_at < started_at)),
 ('BR06 sahip olunmayan oyuna yorum',
   (SELECT count(*) FROM review r WHERE NOT EXISTS
       (SELECT 1 FROM library_entry le WHERE le.user_id = r.user_id AND le.product_id = r.base_game_id))),
 ('BR06 puan 1-5 dışında',
   (SELECT count(*) FROM review WHERE rating NOT BETWEEN 1 AND 5)),
 ('§5 completed_at < created_at',
   (SELECT count(*) FROM store_order WHERE completed_at < created_at))
) AS t(kural, ihlal);

\echo '== BR07 gösterimi: satış fiyatı güncel list_price''tan farklı olan kalemler (bilgi amaçlı)'
SELECT count(*) AS farkli_fiyatli_kalem,
       round(100.0 * count(*) / (SELECT count(*) FROM order_item), 1) AS yuzde
  FROM order_item oi JOIN product p USING (product_id)
 WHERE oi.unit_price_paid <> p.list_price;

\echo '== data_loader yükleme sonrası yetkisi (false beklenir)'
SELECT has_table_privilege('data_loader', 'store_order', 'INSERT') AS store_order_insert,
       has_table_privilege('data_loader', 'app_user', 'INSERT')    AS app_user_insert,
       has_database_privilege('data_loader', current_database(), 'TEMPORARY') AS temp_yetkisi;

\echo '== Tablo ve indeks boyutları'
SELECT c.relname AS tablo,
       pg_size_pretty(pg_table_size(c.oid))   AS tablo_boyutu,
       pg_size_pretty(pg_indexes_size(c.oid)) AS indeks_boyutu
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
 ORDER BY pg_total_relation_size(c.oid) DESC;
