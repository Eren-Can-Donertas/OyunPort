-- =====================================================================
-- OyunPort  V5: Veritabanı rolleri, yetkiler ve satır güvenliği (rapor §7.2)
--
--   db_admin            şema sahibi / migration (bu betiği çalıştıran rol; 01-create-db-admin.sh)
--   app_customer        katalog okuma; kendi verisi (RLS); satın alma, yorum, oturum
--   app_admin           yönetim havuzu: katalog DML + rapor view'ları; süper kullanıcı değil
--   reporting_readonly  yalnızca analitik view'lar ve katalog SELECT
--   data_loader         yalnızca yükleme sırasında geçici INSERT (datagen/load.sh verir ve geri alır)
--
-- PUBLIC'ten tüm yetkiler geri alınır; her yetki açıkça verilir.
-- Kullanıcı kimliği backend tarafından her transaction'da
--   SELECT set_config('app.user_id', ?, true)   (SET LOCAL eşdeğeri)
-- ile taşınır ve RLS politikaları app_current_user_id() ile okur.
-- Parolalar Flyway placeholder'larıyla ortam değişkenlerinden gelir.
-- =====================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_customer') THEN
        CREATE ROLE app_customer LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
            PASSWORD '${appCustomerPassword}';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_admin') THEN
        CREATE ROLE app_admin LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
            PASSWORD '${appAdminPassword}';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reporting_readonly') THEN
        CREATE ROLE reporting_readonly LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
            PASSWORD '${reportingPassword}';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'data_loader') THEN
        CREATE ROLE data_loader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
            PASSWORD '${dataLoaderPassword}';
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- PUBLIC yetkilerinin geri alınması
-- ---------------------------------------------------------------------
DO $$
BEGIN
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO app_customer, app_admin, reporting_readonly, data_loader',
                   current_database());
END $$;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO app_customer, app_admin, reporting_readonly, data_loader;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE db_admin REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- ---------------------------------------------------------------------
-- Oturum kimliği yardımcı fonksiyonu
-- ---------------------------------------------------------------------
CREATE FUNCTION app_current_user_id() RETURNS BIGINT
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT nullif(current_setting('app.user_id', true), '')::bigint
$$;
REVOKE EXECUTE ON FUNCTION app_current_user_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_current_user_id() TO app_customer, app_admin;

-- ---------------------------------------------------------------------
-- Yetkili servis fonksiyonları (Tasarım notları Ç6)
-- Giriş anında kullanıcı kimliği henüz bilinmediğinden RLS altında app_user
-- okunamaz; kayıt ve giriş yalnızca bu dar kapsamlı fonksiyonlarla yapılır.
-- ---------------------------------------------------------------------
CREATE FUNCTION api_register_customer(p_email TEXT, p_display_name TEXT, p_password_hash TEXT)
RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_user_id BIGINT;
BEGIN
    INSERT INTO app_user (email, display_name, password_hash)
    VALUES (lower(btrim(p_email)), btrim(p_display_name), p_password_hash)
    RETURNING user_id INTO v_user_id;

    INSERT INTO user_role (user_id, role_id)
    SELECT v_user_id, role_id FROM app_role WHERE role_name = 'CUSTOMER';

    RETURN v_user_id;
END $$;

CREATE FUNCTION api_login_lookup(p_email TEXT)
RETURNS TABLE (user_id BIGINT, email TEXT, display_name TEXT, password_hash TEXT,
               is_active BOOLEAN, roles TEXT[])
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT u.user_id, u.email::text, u.display_name::text, u.password_hash::text, u.is_active,
           coalesce(array_agg(r.role_name::text ORDER BY r.role_name)
                        FILTER (WHERE r.role_name IS NOT NULL), ARRAY[]::text[])
      FROM app_user u
      LEFT JOIN user_role ur ON ur.user_id = u.user_id
      LEFT JOIN app_role r   ON r.role_id = ur.role_id
     WHERE u.email = lower(btrim(p_email))
     GROUP BY u.user_id
$$;

REVOKE EXECUTE ON FUNCTION api_register_customer(TEXT, TEXT, TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION api_login_lookup(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION api_register_customer(TEXT, TEXT, TEXT) TO app_customer;
GRANT EXECUTE ON FUNCTION api_login_lookup(TEXT) TO app_customer;

-- ---------------------------------------------------------------------
-- app_customer
-- ---------------------------------------------------------------------
GRANT SELECT ON publisher, developer, genre, product, base_game, dlc,
                product_genre, product_developer TO app_customer;
GRANT SELECT ON v_product_rating, v_public_review TO app_customer;

-- Kendi verisi (RLS ile sınırlı). app_user UPDATE yetkisi, T2'deki
-- "SELECT ... FROM app_user ... FOR UPDATE" kilidi ve profil güncellemesi içindir.
GRANT SELECT, UPDATE (display_name, password_hash) ON app_user TO app_customer;
GRANT SELECT, INSERT, UPDATE (status, completed_at) ON store_order TO app_customer;
GRANT SELECT, INSERT ON order_item TO app_customer;
GRANT SELECT, INSERT ON payment_attempt TO app_customer;
GRANT SELECT, INSERT ON library_entry TO app_customer;
GRANT SELECT, INSERT, UPDATE (ended_at) ON play_session TO app_customer;
GRANT SELECT, INSERT, UPDATE (rating, comment, updated_at) ON review TO app_customer;
GRANT SELECT, INSERT, DELETE ON wishlist TO app_customer;
GRANT SELECT ON v_order_summary, v_library_playtime TO app_customer;

-- ---------------------------------------------------------------------
-- app_admin  (katalog DML; ürün/kullanıcı silme yok -> BR08 pasife alma)
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON publisher, developer, genre TO app_admin;
GRANT SELECT, INSERT, UPDATE (publisher_id, title, description, list_price, release_date, is_active)
      ON product TO app_admin;
GRANT SELECT, INSERT, UPDATE (minimum_requirements) ON base_game TO app_admin;
GRANT SELECT, INSERT, UPDATE (content_summary) ON dlc TO app_admin;
GRANT SELECT, INSERT, DELETE ON product_genre, product_developer TO app_admin;
GRANT SELECT ON v_product_rating, v_public_review, v_game_sales_monthly,
                rpt_overview, rpt_user_activity_monthly,
                rpt_q1_monthly_top_publishers, rpt_q2_genre_top_sellers,
                rpt_q3_above_average_customers, rpt_q4_low_playtime_games,
                rpt_q5_dlc_conversion TO app_admin;

-- ---------------------------------------------------------------------
-- reporting_readonly  (INSERT/UPDATE/DELETE yok; kullanıcı/sipariş tablolarına doğrudan erişim yok)
-- ---------------------------------------------------------------------
GRANT SELECT ON publisher, developer, genre, product, base_game, dlc,
                product_genre, product_developer TO reporting_readonly;
GRANT SELECT ON v_game_sales_monthly, v_product_rating,
                rpt_overview, rpt_user_activity_monthly,
                rpt_q1_monthly_top_publishers, rpt_q2_genre_top_sellers,
                rpt_q3_above_average_customers, rpt_q4_low_playtime_games,
                rpt_q5_dlc_conversion TO reporting_readonly;

-- ---------------------------------------------------------------------
-- Satır güvenliği (RLS)
-- Tablo sahibi db_admin ve sahibin yetkisiyle çalışan rapor view'ları RLS'e tabi değildir.
-- data_loader politikaları yalnızca yükleme sırasında INSERT yetkisi verildiğinde etkilidir.
-- ---------------------------------------------------------------------
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
CREATE POLICY app_user_customer_self ON app_user FOR ALL TO app_customer
    USING (user_id = app_current_user_id())
    WITH CHECK (user_id = app_current_user_id());
CREATE POLICY app_user_loader ON app_user FOR ALL TO data_loader USING (true) WITH CHECK (true);

ALTER TABLE store_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY store_order_customer_own ON store_order FOR ALL TO app_customer
    USING (user_id = app_current_user_id())
    WITH CHECK (user_id = app_current_user_id());
CREATE POLICY store_order_loader ON store_order FOR ALL TO data_loader USING (true) WITH CHECK (true);

ALTER TABLE order_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY order_item_customer_own ON order_item FOR ALL TO app_customer
    USING (EXISTS (SELECT 1 FROM store_order so
                    WHERE so.order_id = order_item.order_id
                      AND so.user_id = app_current_user_id()))
    WITH CHECK (EXISTS (SELECT 1 FROM store_order so
                         WHERE so.order_id = order_item.order_id
                           AND so.user_id = app_current_user_id()));
CREATE POLICY order_item_loader ON order_item FOR ALL TO data_loader USING (true) WITH CHECK (true);

ALTER TABLE payment_attempt ENABLE ROW LEVEL SECURITY;
CREATE POLICY payment_attempt_customer_own ON payment_attempt FOR ALL TO app_customer
    USING (EXISTS (SELECT 1 FROM store_order so
                    WHERE so.order_id = payment_attempt.order_id
                      AND so.user_id = app_current_user_id()))
    WITH CHECK (EXISTS (SELECT 1 FROM store_order so
                         WHERE so.order_id = payment_attempt.order_id
                           AND so.user_id = app_current_user_id()));
CREATE POLICY payment_attempt_loader ON payment_attempt FOR ALL TO data_loader USING (true) WITH CHECK (true);

ALTER TABLE library_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY library_entry_customer_own ON library_entry FOR ALL TO app_customer
    USING (user_id = app_current_user_id())
    WITH CHECK (user_id = app_current_user_id());
CREATE POLICY library_entry_loader ON library_entry FOR ALL TO data_loader USING (true) WITH CHECK (true);

ALTER TABLE play_session ENABLE ROW LEVEL SECURITY;
CREATE POLICY play_session_customer_own ON play_session FOR ALL TO app_customer
    USING (EXISTS (SELECT 1 FROM library_entry le
                    WHERE le.library_id = play_session.library_id
                      AND le.user_id = app_current_user_id()))
    WITH CHECK (EXISTS (SELECT 1 FROM library_entry le
                         WHERE le.library_id = play_session.library_id
                           AND le.user_id = app_current_user_id()));
CREATE POLICY play_session_loader ON play_session FOR ALL TO data_loader USING (true) WITH CHECK (true);

ALTER TABLE review ENABLE ROW LEVEL SECURITY;
CREATE POLICY review_customer_own ON review FOR ALL TO app_customer
    USING (user_id = app_current_user_id())
    WITH CHECK (user_id = app_current_user_id());
CREATE POLICY review_loader ON review FOR ALL TO data_loader USING (true) WITH CHECK (true);

ALTER TABLE wishlist ENABLE ROW LEVEL SECURITY;
CREATE POLICY wishlist_customer_own ON wishlist FOR ALL TO app_customer
    USING (user_id = app_current_user_id())
    WITH CHECK (user_id = app_current_user_id());
CREATE POLICY wishlist_loader ON wishlist FOR ALL TO data_loader USING (true) WITH CHECK (true);

ALTER TABLE user_role ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_role_loader ON user_role FOR ALL TO data_loader USING (true) WITH CHECK (true);
