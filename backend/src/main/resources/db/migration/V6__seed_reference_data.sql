-- =====================================================================
-- OyunPort  V6: Referans verisi
--   * APP_ROLE: CUSTOMER, ADMIN
--   * Demo yönetici hesabı (her iki role de sahip; rapor §1.4)
--       e-posta: admin@oyunport.local   parola: Admin123!   (yalnızca geliştirme/demo)
-- Katalog ve işlem verisi bu migration'da DEĞİL, datagen/ altındaki sentetik veri
-- üreticisi ile yüklenir (datagen yalnızca bu seed dışında boş bir veritabanına yükler).
-- =====================================================================

INSERT INTO app_role (role_name) VALUES ('CUSTOMER'), ('ADMIN');

INSERT INTO app_user (email, display_name, password_hash)
VALUES ('admin@oyunport.local', 'OyunPort Yönetici',
        '$2b$10$9eey.LjCrLLOX0vqIAGwq.Usgn7xjlGu9L1RgZRxvpCL37mo8sBpO');

INSERT INTO user_role (user_id, role_id)
SELECT u.user_id, r.role_id
  FROM app_user u CROSS JOIN app_role r
 WHERE u.email = 'admin@oyunport.local';
