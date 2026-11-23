-- Çalıştıran rol: db_admin
-- Yükleme sonrası: data_loader yetkileri geri alınır (rapor §7.2), identity sayaçları
-- açık ID'lerle yüklenen satırların üzerine taşınır, istatistikler güncellenir.
\set ON_ERROR_STOP on

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM data_loader;
DO $$
BEGIN
    EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM data_loader', current_database());
END $$;

SELECT setval(pg_get_serial_sequence('app_user', 'user_id'),         (SELECT max(user_id) FROM app_user));
SELECT setval(pg_get_serial_sequence('genre', 'genre_id'),           (SELECT max(genre_id) FROM genre));
SELECT setval(pg_get_serial_sequence('publisher', 'publisher_id'),   (SELECT max(publisher_id) FROM publisher));
SELECT setval(pg_get_serial_sequence('developer', 'developer_id'),   (SELECT max(developer_id) FROM developer));
SELECT setval(pg_get_serial_sequence('product', 'product_id'),       (SELECT max(product_id) FROM product));
SELECT setval(pg_get_serial_sequence('store_order', 'order_id'),     (SELECT max(order_id) FROM store_order));
SELECT setval(pg_get_serial_sequence('payment_attempt', 'payment_id'), (SELECT max(payment_id) FROM payment_attempt));
SELECT setval(pg_get_serial_sequence('library_entry', 'library_id'), (SELECT max(library_id) FROM library_entry));
SELECT setval(pg_get_serial_sequence('play_session', 'session_id'),  (SELECT max(session_id) FROM play_session));

VACUUM (ANALYZE) app_user, app_role, user_role, publisher, developer, genre, product, base_game, dlc,
                 product_genre, product_developer, wishlist, store_order, order_item, payment_attempt,
                 library_entry, play_session, review;

SELECT 'data_loader yetkileri geri alındı; sayaçlar ve istatistikler güncellendi' AS durum,
       has_table_privilege('data_loader', 'store_order', 'INSERT') AS loader_insert_kaldi;
