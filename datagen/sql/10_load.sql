-- Çalıştıran rol: data_loader   (psql istemcisi db konteynerinde; CSV'ler /datagen altında)
-- PostgreSQL COPY + kontrollü batch yükleme (rapor §8.1).
--
-- PostgreSQL, RLS etkin tablolara (app_user, user_role, store_order, order_item, payment_attempt,
-- library_entry, play_session, review, wishlist) RLS'e tabi bir rolle COPY FROM yapılmasına izin vermez
-- ("COPY FROM not supported with row-level security"; Tasarım notları Ç16). Bu tablolar için CSV önce
-- oturuma özel TEMP hazırlık tablosuna COPY ile alınır, ardından INSERT ... SELECT ile asıl tabloya
-- aktarılır; böylece satırlar RLS WITH CHECK politikalarından ve trigger'lardan geçer.
-- RLS olmayan katalog tabloları doğrudan COPY ile yüklenir.
--
-- Trigger ve kısıtlar KAPATILMAZ; yükleme sırası canlı iş akışını izler (Tasarım notları Ç15):
--   TX1 hesaplar + katalog  ->  TX2 siparişler PENDING + kalemler
--   TX3 ödemeler + durum güncellemesi (SUCCESS => COMPLETED aynı transaction'da, BR09)
--   TX4 kütüphane (yalnızca COMPLETED + SUCCESS kalemlerden, BR04/BR05)
--   TX5 oynama oturumları, yorumlar, istek listesi
\set ON_ERROR_STOP on
\timing on

\echo '== TX1: APP_USER, USER_ROLE ve katalog'
BEGIN;
CREATE TEMP TABLE stg_app_user (user_id BIGINT, email VARCHAR(254), display_name VARCHAR(60),
                                password_hash VARCHAR(100), created_at TIMESTAMPTZ, is_active BOOLEAN) ON COMMIT DROP;
\copy stg_app_user FROM '/datagen/app_user.csv' WITH (FORMAT csv)
INSERT INTO app_user (user_id, email, display_name, password_hash, created_at, is_active)
SELECT user_id, email, display_name, password_hash, created_at, is_active FROM stg_app_user;
INSERT INTO user_role (user_id, role_id)
SELECT s.user_id, r.role_id FROM stg_app_user s CROSS JOIN app_role r WHERE r.role_name = 'CUSTOMER';
\copy genre (genre_id, name) FROM '/datagen/genre.csv' WITH (FORMAT csv)
\copy publisher (publisher_id, name, country) FROM '/datagen/publisher.csv' WITH (FORMAT csv)
\copy developer (developer_id, name, country) FROM '/datagen/developer.csv' WITH (FORMAT csv)
\copy product (product_id, publisher_id, title, description, list_price, release_date, is_active) FROM '/datagen/product.csv' WITH (FORMAT csv)
\copy base_game (product_id, minimum_requirements) FROM '/datagen/base_game.csv' WITH (FORMAT csv)
\copy dlc (product_id, base_game_id, content_summary) FROM '/datagen/dlc.csv' WITH (FORMAT csv)
\copy product_genre (product_id, genre_id) FROM '/datagen/product_genre.csv' WITH (FORMAT csv)
\copy product_developer (product_id, developer_id) FROM '/datagen/product_developer.csv' WITH (FORMAT csv)
COMMIT;

\echo '== TX2: STORE_ORDER (PENDING) ve ORDER_ITEM'
BEGIN;
CREATE TEMP TABLE stg_store_order (order_id BIGINT, user_id BIGINT, created_at TIMESTAMPTZ, checkout_key UUID) ON COMMIT DROP;
CREATE TEMP TABLE stg_order_item (order_id BIGINT, line_no INTEGER, product_id BIGINT, unit_price_paid NUMERIC(12,2)) ON COMMIT DROP;
\copy stg_store_order FROM '/datagen/store_order.csv' WITH (FORMAT csv)
\copy stg_order_item FROM '/datagen/order_item.csv' WITH (FORMAT csv)
INSERT INTO store_order (order_id, user_id, status, created_at, checkout_key)
SELECT order_id, user_id, 'PENDING', created_at, checkout_key FROM stg_store_order;
INSERT INTO order_item (order_id, line_no, product_id, unit_price_paid)
SELECT order_id, line_no, product_id, unit_price_paid FROM stg_order_item;
COMMIT;

\echo '== TX3: PAYMENT_ATTEMPT ve sipariş durum güncellemesi'
BEGIN;
CREATE TEMP TABLE stg_payment_attempt (payment_id BIGINT, order_id BIGINT, amount NUMERIC(12,2), status VARCHAR(10),
                                       attempted_at TIMESTAMPTZ, request_key UUID) ON COMMIT DROP;
CREATE TEMP TABLE stg_order_final (order_id BIGINT PRIMARY KEY, status VARCHAR(10) NOT NULL, completed_at TIMESTAMPTZ) ON COMMIT DROP;
\copy stg_payment_attempt FROM '/datagen/payment_attempt.csv' WITH (FORMAT csv)
\copy stg_order_final FROM '/datagen/order_final_status.csv' WITH (FORMAT csv)
INSERT INTO payment_attempt (payment_id, order_id, amount, status, attempted_at, request_key)
SELECT payment_id, order_id, amount, status, attempted_at, request_key FROM stg_payment_attempt ORDER BY payment_id;
UPDATE store_order so
   SET status = f.status, completed_at = f.completed_at
  FROM stg_order_final f
 WHERE f.order_id = so.order_id;
COMMIT;

\echo '== TX4: LIBRARY_ENTRY'
BEGIN;
CREATE TEMP TABLE stg_library_entry (library_id BIGINT, user_id BIGINT, product_id BIGINT, order_id BIGINT,
                                     line_no INTEGER, acquired_at TIMESTAMPTZ) ON COMMIT DROP;
\copy stg_library_entry FROM '/datagen/library_entry.csv' WITH (FORMAT csv)
INSERT INTO library_entry (library_id, user_id, product_id, order_id, line_no, acquired_at)
SELECT library_id, user_id, product_id, order_id, line_no, acquired_at FROM stg_library_entry;
COMMIT;

\echo '== TX5: PLAY_SESSION, REVIEW, WISHLIST'
BEGIN;
CREATE TEMP TABLE stg_play_session (session_id BIGINT, library_id BIGINT, started_at TIMESTAMPTZ, ended_at TIMESTAMPTZ) ON COMMIT DROP;
CREATE TEMP TABLE stg_review (user_id BIGINT, base_game_id BIGINT, rating SMALLINT, comment VARCHAR(2000),
                              created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ) ON COMMIT DROP;
CREATE TEMP TABLE stg_wishlist (user_id BIGINT, product_id BIGINT, added_at TIMESTAMPTZ) ON COMMIT DROP;
\copy stg_play_session FROM '/datagen/play_session.csv' WITH (FORMAT csv)
\copy stg_review FROM '/datagen/review.csv' WITH (FORMAT csv)
\copy stg_wishlist FROM '/datagen/wishlist.csv' WITH (FORMAT csv)
INSERT INTO play_session (session_id, library_id, started_at, ended_at)
SELECT session_id, library_id, started_at, ended_at FROM stg_play_session;
INSERT INTO review (user_id, base_game_id, rating, comment, created_at, updated_at)
SELECT user_id, base_game_id, rating, comment, created_at, updated_at FROM stg_review;
INSERT INTO wishlist (user_id, product_id, added_at)
SELECT user_id, product_id, added_at FROM stg_wishlist;
COMMIT;
