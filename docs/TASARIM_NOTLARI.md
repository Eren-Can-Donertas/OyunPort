# Tasarım notları: çelişkiler, kararlar ve varsayımlar

Bu belge ara raporun (`docs/BIL372_OyunPort_Ara_Rapor.pdf`, 19 Ekim 2026) final uygulamasına
dönüştürülürken karşılaşılan **çelişkileri / belirsizlikleri**, alınan **kararları** ve
**varsayımları** listeler. Domain, entity ilişkileri, iş kuralları ve teknoloji seçimleri
değiştirilmemiştir; rapordaki 18 tablo birebir uygulanmış, yeni tablo eklenmemiştir.

## 1. Tespit edilen çelişkiler ve belirsizlikler

| # | Rapordaki ifade | Sorun | Karar |
|---|---|---|---|
| Ç1 | FR04 ve "Sepet ve ödeme" menüsü; ancak §4 şemasında CART tablosu yok. | Sepetin nerede tutulacağı tanımsız. | Yeni tablo eklenmedi. Sepet istemci tarafında (tarayıcı `localStorage`) tutulur; her değişiklikte `POST /api/cart/validate` ile **sunucuda** fiyat, aktiflik, sahiplik ve DLC koşulu doğrulanır. Sepet T1'de PENDING siparişe dönüşür. |
| Ç2 | BR09 yalnızca PENDING/COMPLETED'dan bahseder; §8.1 "30.000 pending/iptal" sipariş ister. | İptal durumu ve iptal akışı tanımsız. | `store_order.status` CHECK değerleri `PENDING`, `COMPLETED`, `CANCELLED`. Müşteri yalnızca kendi PENDING siparişini iptal edebilir (asgari akış). COMPLETED/CANCELLED siparişler değiştirilemez (BR08, trigger). |
| Ç3 | §7.1: "FAILED sonucu ayrı transaction'da saklanır" ve T2 kilit sırası. | Simülasyonun kilitlerden önce mi sonra mı çalışacağı belirsiz. | T2 kullanıcı ve sipariş satırlarını kilitler, sahiplik/DLC kontrolünü yapar, sonra yerel simülasyonu çağırır. FAILED ise o transaction **yalnızca** `PAYMENT_ATTEMPT(FAILED)` yazar ve commit eder; SUCCESS ise ödeme + kütüphane + COMPLETED tek transaction'da yazılır. Böylece başarısız deneme ile başarılı tamamlama her zaman ayrı transaction'lardır ve yarım sahiplik oluşmaz. |
| Ç4 | §4.4: sipariş toplamı saklanmaz; `PAYMENT_ATTEMPT.amount` toplamla "tutarlı" olmalı. | Tutarlılığın nerede denetleneceği belirsiz. | `payment_attempt` BEFORE INSERT trigger'ı `amount = SUM(order_item.unit_price_paid)` şartını zorlar; servis tutarı veritabanından hesaplar, istemciden almaz. |
| Ç5 | §7.2: app_customer için RLS; §4.5 view'ları (`v_order_summary`, `v_library_playtime`). | PostgreSQL view'ları varsayılan olarak sahibinin yetkisiyle çalışır ve RLS'i atlar. | Müşterinin kendi verisini gösteren view'lar `security_invoker = true` ile tanımlandı (RLS uygulanır). Rapor view'ları (`v_game_sales_monthly`, `rpt_*`) sahibin yetkisiyle çalışır ve yalnızca `app_admin`/`reporting_readonly`'ye açılır. Yorum listesi ve ortalama puan, e-posta içermeyen ayrı view'larla (`v_public_review`, `v_product_rating`) sunulur. |
| Ç6 | RLS + "kimlik doğrulanmış oturumdan SET LOCAL". | Giriş anında kullanıcı kimliği henüz bilinmez; RLS altında `app_user` okunamaz. | Kayıt ve giriş için `SECURITY DEFINER` servis fonksiyonları: `api_register_customer`, `api_login_lookup` (raporda "yetkili servis fonksiyonları"). |
| Ç7 | §7.1 T2 pseudocode'u `SELECT ... FROM app_user ... FOR UPDATE` kullanır. | `FOR UPDATE` için tablo üzerinde UPDATE yetkisi gerekir. | `app_customer`'a yalnızca `app_user(display_name, password_hash)` sütun-düzeyi UPDATE verildi; RLS yalnızca kendi satırına izin verir. Aynı yetki profil güncellemesini de karşılar. |
| Ç8 | §5: "CHECK ile başka tablo sorgulanmaz"; §4.3 LIBRARY_ENTRY kaynak tutarlılığı. | Kaynak kalem, sipariş sahibi, başarılı ödeme ve DLC ön koşulu FK ile ifade edilemez. | `DEFERRABLE INITIALLY DEFERRED` constraint trigger'lar (commit anında denetim). Ayrıntı: `V2__integrity_triggers.sql`. |
| Ç9 | v_library_playtime "tamamlanmış oturumlar". | `ended_at` boş olabilir mi? | `ended_at` NULL olabilir (devam eden oturum). Ek destekleyici kısıt: bir kütüphane kaydında aynı anda en fazla bir açık oturum (kısmi UNIQUE indeks); oturum edinimden önce başlayamaz. |
| Ç10 | BR07 ve UC01: "fiyat sunucuda yeniden hesaplanır". | Fiyatın T1'de mi T2'de mi sabitleneceği belirsiz. | `unit_price_paid` T1'de güncel `list_price` değerinden **sunucuda** yazılır; kalemler sonradan değiştirilemez (trigger). T1–T2 arasında fiyat değişse de sipariş tutarı değişmez. |
| Ç11 | PAYMENT_ATTEMPT yalnızca amount/status/attempted_at/request_key içerir. | Başarısızlık nedeni saklanamaz. | Şema değiştirilmedi; neden yalnızca API yanıtında döner, veritabanında yalnızca `FAILED` durumu tutulur. |
| Ç12 | PUBLISHER/DEVELOPER için raporda UQ yok. | Aynı adla iki publisher oluşabilir. | Rapora sadık kalındı (UQ eklenmedi); gözlem olarak not edildi. GENRE.name UQ uygulandı. |
| Ç13 | REVIEW için FK yalnızca LIBRARY_ENTRY(user_id, product_id) ve BASE_GAME'e. | APP_USER'a doğrudan FK yok. | Rapordaki gibi bırakıldı: kullanıcı varlığı LIBRARY_ENTRY üzerinden dolaylı olarak garanti edilir. |
| Ç14 | Q2–Q5'te eşik değerleri (HAVING) belirtilmemiş (Q5 hariç). | Eşikler veri hacmine bağlı. | Q2: ürün başına ≥ 25 satış, tür başına ilk 5; Q4: ≥ 20 uygun sahip; Q5: raporda belirtildiği gibi ≥ 100 sahip. Q5'te yalnızca aktif kullanıcılar sayılır (APP_USER join'inin amacı). |
| Ç15 | §8.1 "kalem/sahiplik/ödeme koşulları üretimde sağlanacak" ve trigger'lar. | Toplu yüklemede trigger'ların kapatılıp kapatılmayacağı belirsiz. | Trigger'lar **kapatılmadı**. Yükleme sırası gerçek iş akışını izler: siparişler PENDING yüklenir → kalemler → ödemeler + durum güncellemesi (aynı transaction) → kütüphane → oturum/yorum/istek listesi. Böylece yüklenen her satır canlı sistemle aynı kısıtlardan geçer. |

| Ç16 | §7.2 "app_customer için satır güvenliği" + §8.1 "PostgreSQL COPY ile aktarılacak" + `data_loader` yalnızca INSERT. | **Gerçek yüklemede ortaya çıktı:** PostgreSQL, RLS etkin bir tabloya RLS'e tabi bir rolle `COPY FROM` yapılmasını reddeder (`ERROR: COPY FROM not supported with row-level security`). `BYPASSRLS` yalnızca süper kullanıcı tarafından verilebilir ve en az yetki ilkesine aykırıdır. | RLS'li 9 tablo için CSV önce oturuma özel `TEMP` hazırlık tablosuna `COPY` ile alınır, sonra `INSERT … SELECT` ile asıl tabloya aktarılır (raporun "COPY ve kontrollü batch yükleme" ifadesiyle uyumlu). Satırlar `data_loader` RLS politikaları ve tüm trigger'lardan geçer. `data_loader`'a yükleme süresince `TEMPORARY` yetkisi de verilir ve yükleme sonunda geri alınır. Katalog tabloları (RLS yok) doğrudan `COPY` ile yüklenir. |

## 2. Varsayımlar

- Tüm tutarlar TRY, `NUMERIC(12,2)`. Zaman damgaları `TIMESTAMPTZ`; aylık raporlar `Europe/Istanbul` saat dilimine göre gruplanır.
- E-posta küçük harfle saklanır (CHECK) ve giriş anahtarıdır; profil ekranında değiştirilemez (görünen ad ve parola değiştirilebilir).
- Ödeme simülasyonu istemcinin seçtiği demo sonucu (`SUCCESS`, `CARD_DECLINED`, `INSUFFICIENT_FUNDS`) ile çalışır; kart verisi alınmaz/saklanmaz.
- Uygulama düzeyi roller `CUSTOMER` ve `ADMIN`; seed admin hesabı her iki role sahiptir (rapor §1.4). Kayıt olan kullanıcı `CUSTOMER` alır.
- Admin uç noktaları `app_admin` havuzunu, müşteri uç noktaları `app_customer` havuzunu kullanır. `reporting_readonly` web uygulamasında kullanılmaz; analist erişimi ve güvenlik testleri için tanımlıdır.
- JWT oturum süresi 8 saattir; çıkış istemci tarafında token silinerek yapılır.
- Sentetik kullanıcıların tümü aynı demo parolasını paylaşır (parola hash'i bir kez hesaplanmıştır).

## 3. Uygulama sırasında yapılan diğer gözlemler

- **Geçersiz/süresi dolmuş token ile herkese açık katalog çağrısı** anonim kabul edilir (401 dönmez); `owned`/`wishlisted`
  bayrakları false olur. Arayüz token süresi dolunca oturumu kapatarak bunu telafi eder.
- **JWT içindeki roller** oturum açıldığı andaki rollerdir; rol değişikliği yeniden girişte yansır (token ömrü 8 saat).
- **DLC'nin bağlı olduğu ana oyun** ürün oluşturulduktan sonra değiştirilemez (`app_admin`'in `dlc.base_game_id` üzerinde
  UPDATE yetkisi yoktur); mevcut sahiplerin BR05 geçmişi bozulmasın diye bilinçli karar.
- **Tekrarlanan (idempotent) başarısız ödeme yanıtında** başarısızlık nedeni boştur, çünkü neden saklanmaz (Ç11).
- **PostgreSQL ayarları:** `random_page_cost = 4` ve `jit = on` varsayılanlarının bu veri hacminde bazı planları
  kötüleştirdiği ölçüldü (`docs/PERFORMANS.md` §3–4). Ayarlar değiştirilmedi, öneri olarak belgelendi.

## 4. Eksikler ve bilinen sınırlar

- Frontend için tarayıcı tabanlı otomatik E2E test paketi yoktur (bkz. `docs/TEST_SONUCLARI.md` §4).
- Yük testi ve soğuk önbellek performans ölçümü yapılmamıştır.
- Seed admin ve sentetik kullanıcı parolaları demo amaçlı sabittir; üretim kullanımı için değiştirilmelidir.
- Proje klasörü bir git deposu değildir; teslimden önce sürüm kontrolüne alınması önerilir.

## 5. Kapsam dışı (raporla uyumlu)

Gerçek ödeme/banka entegrasyonu, kart verisi saklama, oyun dosyası dağıtımı, launcher, DRM,
arkadaşlık/sohbet, iade, hediye, abonelik, çoklu para birimi uygulanmamıştır.
