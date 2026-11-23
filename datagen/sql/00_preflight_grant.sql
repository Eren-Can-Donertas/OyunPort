-- Çalıştıran rol: db_admin
-- 1) Veritabanının temiz (yalnızca V6 seed) olduğunu doğrular.
-- 2) data_loader rolüne YALNIZCA yükleme süresince gereken yetkileri verir (rapor §7.2).
\set ON_ERROR_STOP on

DO $$
BEGIN
    IF (SELECT count(*) FROM flyway_schema_history WHERE success) < 6 THEN
        RAISE EXCEPTION 'Migration''lar uygulanmamış. Önce backend''i başlatın (docker compose up -d).';
    END IF;
    IF (SELECT count(*) FROM product) > 0 OR (SELECT coalesce(max(user_id), 0) FROM app_user) > 1 THEN
        RAISE EXCEPTION 'Veritabanı boş değil. Sentetik veri yalnızca temiz kuruluma yüklenir (docker compose down -v && docker compose up -d).';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app_role WHERE role_name = 'CUSTOMER') THEN
        RAISE EXCEPTION 'CUSTOMER rolü bulunamadı (V6 seed eksik).';
    END IF;
    EXECUTE format('GRANT TEMPORARY ON DATABASE %I TO data_loader', current_database());
END $$;

GRANT SELECT ON app_role TO data_loader;
GRANT SELECT, INSERT ON app_user, user_role, publisher, developer, genre, product, base_game, dlc,
                        product_genre, product_developer, store_order, order_item, payment_attempt,
                        library_entry, play_session, review, wishlist TO data_loader;
GRANT UPDATE (status, completed_at) ON store_order TO data_loader;

SELECT 'data_loader geçici yetkileri verildi' AS durum;
