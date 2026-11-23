-- =====================================================================
-- OyunPort  V2: Tablolar arası bütünlük kuralları (rapor §4.2, §4.3, §5)
--
-- CHECK başka tablo sorgulayamadığı için bu kurallar trigger ile korunur.
-- Commit anında doğrulanması gereken kurallar DEFERRABLE INITIALLY DEFERRED
-- constraint trigger'dır. Hata kodu: 23514 (check_violation), mesaj [BRxx] ile başlar.
--
-- Trigger fonksiyonları SECURITY DEFINER'dır: tetikleyen rol (app_customer,
-- data_loader) RLS nedeniyle diğer satırları göremese de kural eksiksiz denetlenir.
-- search_path sabitlenmiştir.
-- =====================================================================

-- ---------------------------------------------------------------------
-- BR01  Specialization disjoint: aynı product_id iki alt türde birden olamaz.
-- ---------------------------------------------------------------------
CREATE FUNCTION trg_subtype_disjoint() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    -- Aynı ürün için eşzamanlı alt tür eklemelerini sırala.
    PERFORM 1 FROM product WHERE product_id = NEW.product_id FOR NO KEY UPDATE;
    IF TG_TABLE_NAME = 'base_game' THEN
        IF EXISTS (SELECT 1 FROM dlc WHERE product_id = NEW.product_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = format('[BR01] Ürün %s zaten DLC; aynı ürün BASE_GAME olamaz (disjoint).', NEW.product_id);
        END IF;
    ELSE
        IF EXISTS (SELECT 1 FROM base_game WHERE product_id = NEW.product_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = format('[BR01] Ürün %s zaten BASE_GAME; aynı ürün DLC olamaz (disjoint).', NEW.product_id);
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER base_game_disjoint BEFORE INSERT OR UPDATE OF product_id ON base_game
    FOR EACH ROW EXECUTE FUNCTION trg_subtype_disjoint();
CREATE TRIGGER dlc_disjoint BEFORE INSERT OR UPDATE OF product_id ON dlc
    FOR EACH ROW EXECUTE FUNCTION trg_subtype_disjoint();

-- ---------------------------------------------------------------------
-- BR01  Specialization total: commit edilen PRODUCT alt türsüz kalamaz.
-- ---------------------------------------------------------------------
CREATE FUNCTION trg_product_subtype_total() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_pid BIGINT;
    v_cnt INTEGER;
BEGIN
    v_pid := CASE WHEN TG_TABLE_NAME = 'product' THEN NEW.product_id ELSE OLD.product_id END;
    IF NOT EXISTS (SELECT 1 FROM product WHERE product_id = v_pid) THEN
        RETURN NULL;
    END IF;
    SELECT (SELECT count(*) FROM base_game WHERE product_id = v_pid)
         + (SELECT count(*) FROM dlc WHERE product_id = v_pid)
      INTO v_cnt;
    IF v_cnt <> 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR01] Ürün %s tam olarak bir alt türe (BASE_GAME veya DLC) ait olmalıdır; bulunan alt tür sayısı: %s.', v_pid, v_cnt);
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER product_subtype_total AFTER INSERT ON product
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_product_subtype_total();
CREATE CONSTRAINT TRIGGER base_game_subtype_total AFTER DELETE OR UPDATE OF product_id ON base_game
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_product_subtype_total();
CREATE CONSTRAINT TRIGGER dlc_subtype_total AFTER DELETE OR UPDATE OF product_id ON dlc
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_product_subtype_total();

-- ---------------------------------------------------------------------
-- §4.2  Her ürün en az bir GENRE ve en az bir DEVELOPER ile commit edilir.
-- ---------------------------------------------------------------------
CREATE FUNCTION trg_product_classification() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_pid BIGINT;
BEGIN
    v_pid := CASE WHEN TG_TABLE_NAME = 'product' THEN NEW.product_id ELSE OLD.product_id END;
    IF NOT EXISTS (SELECT 1 FROM product WHERE product_id = v_pid) THEN
        RETURN NULL;   -- ürün silinmişse (CASCADE) denetim gerekmez
    END IF;
    IF NOT EXISTS (SELECT 1 FROM product_genre WHERE product_id = v_pid) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[EER] Ürün %s en az bir GENRE ile ilişkilendirilmelidir (CLASSIFIED_AS total).', v_pid);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM product_developer WHERE product_id = v_pid) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[EER] Ürün %s en az bir DEVELOPER ile ilişkilendirilmelidir (DEVELOPS total).', v_pid);
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER product_classification AFTER INSERT ON product
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_product_classification();
CREATE CONSTRAINT TRIGGER product_genre_minimum AFTER DELETE OR UPDATE ON product_genre
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_product_classification();
CREATE CONSTRAINT TRIGGER product_developer_minimum AFTER DELETE OR UPDATE ON product_developer
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_product_classification();

-- ---------------------------------------------------------------------
-- BR04 / §3  Sipariş 1..N kalem: commit edilen sipariş en az bir kalem içerir.
-- ---------------------------------------------------------------------
CREATE FUNCTION trg_order_has_items() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_oid BIGINT;
BEGIN
    v_oid := CASE WHEN TG_TABLE_NAME = 'store_order' THEN NEW.order_id ELSE OLD.order_id END;
    IF NOT EXISTS (SELECT 1 FROM store_order WHERE order_id = v_oid) THEN
        RETURN NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM order_item WHERE order_id = v_oid) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR04] Sipariş %s en az bir ORDER_ITEM içermelidir.', v_oid);
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER store_order_has_items AFTER INSERT ON store_order
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_order_has_items();
CREATE CONSTRAINT TRIGGER order_item_minimum AFTER DELETE ON order_item
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_order_has_items();

-- ---------------------------------------------------------------------
-- BR08 / BR09  Sipariş durum makinesi.
--   INSERT yalnızca PENDING. PENDING -> COMPLETED (SUCCESS ödeme şart) veya
--   PENDING -> CANCELLED. COMPLETED/CANCELLED sipariş yeniden açılamaz.
--   order_id, user_id, created_at, checkout_key değiştirilemez.
-- ---------------------------------------------------------------------
CREATE FUNCTION trg_store_order_state() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = '[BR09] Sipariş yalnızca PENDING durumunda oluşturulabilir.';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.order_id <> OLD.order_id OR NEW.user_id <> OLD.user_id
       OR NEW.created_at <> OLD.created_at OR NEW.checkout_key <> OLD.checkout_key THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR08] Sipariş %s için order_id, user_id, created_at ve checkout_key değiştirilemez.', OLD.order_id);
    END IF;

    IF OLD.status <> 'PENDING' THEN
        IF NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = format('[BR08] %s durumundaki sipariş %s yeniden açılamaz veya değiştirilemez.', OLD.status, OLD.order_id);
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status = 'COMPLETED' AND NOT EXISTS (
           SELECT 1 FROM payment_attempt WHERE order_id = NEW.order_id AND status = 'SUCCESS') THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR09] Sipariş %s başarılı ödeme denemesi olmadan COMPLETED yapılamaz.', NEW.order_id);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER store_order_state BEFORE INSERT OR UPDATE ON store_order
    FOR EACH ROW EXECUTE FUNCTION trg_store_order_state();

-- ---------------------------------------------------------------------
-- BR07 / BR08  Sipariş kalemleri: yalnızca PENDING siparişe eklenir/silinir,
-- hiçbir zaman güncellenmez (satın alma fiyatı tarihseldir).
-- ---------------------------------------------------------------------
CREATE FUNCTION trg_order_item_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_oid    BIGINT;
    v_status TEXT;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR07] Sipariş kalemi (%s, %s) değiştirilemez; satın alma anındaki fiyat korunur.', OLD.order_id, OLD.line_no);
    END IF;
    v_oid := CASE WHEN TG_OP = 'DELETE' THEN OLD.order_id ELSE NEW.order_id END;
    SELECT status INTO v_status FROM store_order WHERE order_id = v_oid;
    IF v_status IS DISTINCT FROM 'PENDING' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR08] Yalnızca PENDING siparişin kalemleri eklenebilir veya silinebilir (sipariş %s: %s).', v_oid, coalesce(v_status, 'yok'));
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE TRIGGER order_item_guard BEFORE INSERT OR UPDATE OR DELETE ON order_item
    FOR EACH ROW EXECUTE FUNCTION trg_order_item_guard();

-- ---------------------------------------------------------------------
-- BR09 / §4.4  Ödeme denemesi:
--   * yalnızca PENDING sipariş için eklenir,
--   * amount = SUM(order_item.unit_price_paid) (sipariş toplamı saklanmaz),
--   * geçmiş korunur: UPDATE/DELETE yasak.
-- ---------------------------------------------------------------------
CREATE FUNCTION trg_payment_attempt_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_status TEXT;
    v_total  NUMERIC(12,2);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR09] Ödeme denemesi %s geçmiş kaydıdır; değiştirilemez veya silinemez.', OLD.payment_id);
    END IF;
    SELECT status INTO v_status FROM store_order WHERE order_id = NEW.order_id;
    IF v_status IS DISTINCT FROM 'PENDING' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR09] Ödeme denemesi yalnızca PENDING sipariş için başlatılabilir (sipariş %s: %s).', NEW.order_id, coalesce(v_status, 'yok'));
    END IF;
    SELECT coalesce(sum(unit_price_paid), 0) INTO v_total FROM order_item WHERE order_id = NEW.order_id;
    IF NEW.amount <> v_total THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR04] Ödeme tutarı (%s) sipariş %s kalemlerinin toplamına (%s) eşit olmalıdır.', NEW.amount, NEW.order_id, v_total);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER payment_attempt_guard BEFORE INSERT OR UPDATE OR DELETE ON payment_attempt
    FOR EACH ROW EXECUTE FUNCTION trg_payment_attempt_guard();

-- BR09  SUCCESS ödeme aynı transaction içinde siparişi COMPLETED yapmalıdır.
CREATE FUNCTION trg_success_payment_completes_order() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM store_order WHERE order_id = NEW.order_id AND status = 'COMPLETED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR09] SUCCESS ödeme %s, sipariş %s aynı transaction içinde COMPLETED yapılmadan commit edilemez.', NEW.payment_id, NEW.order_id);
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER payment_success_completes_order AFTER INSERT ON payment_attempt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.status = 'SUCCESS')
    EXECUTE FUNCTION trg_success_payment_completes_order();

-- ---------------------------------------------------------------------
-- BR04 / BR05 / §4.3  Kütüphane kaydı (commit anında):
--   * kaynak kalemin ürünü = product_id, sipariş sahibi = user_id,
--   * kaynak sipariş COMPLETED ve SUCCESS ödemeli,
--   * DLC ise kullanıcı ilgili ana oyuna sahip olmalı (aynı sipariş dahil).
-- Sahiplik kalıcıdır: UPDATE/DELETE yasak (rapor §1.3).
-- ---------------------------------------------------------------------
CREATE FUNCTION trg_library_entry_source() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_order_user   BIGINT;
    v_order_status TEXT;
    v_item_product BIGINT;
    v_base_game    BIGINT;
BEGIN
    SELECT so.user_id, so.status, oi.product_id
      INTO v_order_user, v_order_status, v_item_product
      FROM order_item oi
      JOIN store_order so ON so.order_id = oi.order_id
     WHERE oi.order_id = NEW.order_id AND oi.line_no = NEW.line_no;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR04] Kütüphane kaydı %s için kaynak sipariş kalemi (%s, %s) bulunamadı.', NEW.library_id, NEW.order_id, NEW.line_no);
    END IF;
    IF v_order_user <> NEW.user_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR04] Kütüphane kaydının kullanıcısı (%s) sipariş sahibine (%s) eşit olmalıdır.', NEW.user_id, v_order_user);
    END IF;
    IF v_item_product <> NEW.product_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR04] Kütüphane kaydının ürünü (%s) kaynak kalemin ürününe (%s) eşit olmalıdır.', NEW.product_id, v_item_product);
    END IF;
    IF v_order_status <> 'COMPLETED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR04] Kütüphane edinimi yalnızca COMPLETED siparişten oluşur (sipariş %s: %s).', NEW.order_id, v_order_status);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM payment_attempt WHERE order_id = NEW.order_id AND status = 'SUCCESS') THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR04] Sipariş %s için başarılı ödeme yok; kütüphane kaydı oluşturulamaz.', NEW.order_id);
    END IF;

    SELECT base_game_id INTO v_base_game FROM dlc WHERE product_id = NEW.product_id;
    IF FOUND AND NOT EXISTS (
           SELECT 1 FROM library_entry WHERE user_id = NEW.user_id AND product_id = v_base_game) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR05] DLC %s için kullanıcı %s ana oyun %s''a sahip olmalıdır.', NEW.product_id, NEW.user_id, v_base_game);
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER library_entry_source AFTER INSERT ON library_entry
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_library_entry_source();

CREATE FUNCTION trg_library_entry_immutable() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514',
        MESSAGE = format('[BR02] Sahiplik kaydı %s kalıcıdır; değiştirilemez veya silinemez (iade kapsam dışı).', OLD.library_id);
END $$;

CREATE TRIGGER library_entry_immutable BEFORE UPDATE OR DELETE ON library_entry
    FOR EACH ROW EXECUTE FUNCTION trg_library_entry_immutable();

-- ---------------------------------------------------------------------
-- BR06 / §4.3  Oynama oturumu yalnızca BASE_GAME sahipliğine bağlanır;
-- edinimden önce başlayamaz. Bitmiş oturum değiştirilemez.
-- ---------------------------------------------------------------------
CREATE FUNCTION trg_play_session_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_acquired TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.session_id <> OLD.session_id OR NEW.library_id <> OLD.library_id
           OR NEW.started_at <> OLD.started_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = format('[BR06] Oturum %s için library_id ve started_at değiştirilemez.', OLD.session_id);
        END IF;
        IF OLD.ended_at IS NOT NULL AND NEW.ended_at IS DISTINCT FROM OLD.ended_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                MESSAGE = format('[BR06] Tamamlanmış oturum %s değiştirilemez.', OLD.session_id);
        END IF;
        RETURN NEW;
    END IF;

    SELECT le.acquired_at INTO v_acquired
      FROM library_entry le
      JOIN base_game bg ON bg.product_id = le.product_id
     WHERE le.library_id = NEW.library_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR06] Oynama oturumu yalnızca sahip olunan bir BASE_GAME için açılabilir (library_id %s).', NEW.library_id);
    END IF;
    IF NEW.started_at < v_acquired THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('[BR06] Oturum, edinim zamanından (%s) önce başlayamaz.', v_acquired);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER play_session_guard BEFORE INSERT OR UPDATE ON play_session
    FOR EACH ROW EXECUTE FUNCTION trg_play_session_guard();

-- ---------------------------------------------------------------------
-- BR06  Yorum: anahtar ve oluşturma zamanı değişmez; updated_at güncellenir.
-- (Sahiplik ve BASE_GAME koşulu V1'deki FK'ler ile declaratif olarak korunur.)
-- ---------------------------------------------------------------------
CREATE FUNCTION trg_review_update() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF NEW.user_id <> OLD.user_id OR NEW.base_game_id <> OLD.base_game_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = '[BR06] Yorumun kullanıcı, oyun ve oluşturma zamanı değiştirilemez.';
    END IF;
    NEW.updated_at := greatest(now(), OLD.created_at);
    RETURN NEW;
END $$;

CREATE TRIGGER review_update BEFORE UPDATE ON review
    FOR EACH ROW EXECUTE FUNCTION trg_review_update();
