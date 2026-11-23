# Test sonuçları

Bu belgedeki her sayı gerçekten çalıştırılmış bir komutun çıktısıdır (tarih: 2026-11-23). Ham kanıtlar `docs/kanit/` altındadır.

## 1. Özet

| Katman | Komut | Sonuç | Kanıt |
|---|---|---|---|
| Backend entegrasyon (gerçek PostgreSQL 16, Testcontainers) | `scripts/mvn.sh test` | **74 test: 74 başarılı, 0 başarısız, 0 hata, 0 atlandı** (Maven toplam süre 24,9 s) | `docs/kanit/backend_testleri.md`, `docs/kanit/surefire/*.xml` |
| Uçtan uca duman testi (nginx → backend → PostgreSQL, tam veri) | `python3 scripts/e2e_smoke.py` | **22 kontrol: 22 PASS, 0 FAIL** | `docs/kanit/e2e_smoke.txt` |
| Frontend birim testleri + tip denetimi + derleme (temiz `node:22-alpine` konteyneri) | `npm ci && npm test && npm run build` | **33 test: 33 başarılı**; `tsc --noEmit` ve `vite build` hatasız | `docs/kanit/frontend_test_build.txt` |
| Veri yükleme sonrası iş kuralı denetimleri (tam veri) | `datagen/load.sh` (adım 4/4) | **17 denetim, hepsi 0 ihlal (OK)** | `docs/kanit/veri_dogrulama.txt` |
| Gerçek arayüz ekran görüntüleri (headless Chrome, gerçek backend ve veri) | `node scripts/screenshots.mjs` | 16 ekran alındı ve gözle kontrol edildi | `docs/kanit/ekran/*.png` |

## 2. Backend test sınıfları

| Sınıf | Test | Kapsam |
|---|---|---|
| `CheckoutIT` | 17 | T1/T2 uçtan uca, başarısız ödeme + yeniden deneme, request_key/checkout_key idempotency, DLC ön koşulu, çift satın alma, kasıtlı hata ile ROLLBACK, kilitle sıralanan eşzamanlı ödeme (pg_locks ile doğrulanan bekleme), UNIQUE son savunma, BR07 fiyat, iptal, sepet durumları, başka kullanıcının siparişi |
| `SchemaConstraintsIT` | 18 | 18 tablonun varlığı, FK silme/güncelleme davranışları (§5), BR01 total/disjoint, genre/developer zorunluluğu, CHECK'ler, BR03/BR04/BR09 durum makinesi, tek SUCCESS, değişmezlik, kütüphane kaynak tutarlılığı, DB düzeyinde BR05, BR06 oturum/yorum, RESTRICT, idempotency anahtarları — hepsi doğrudan SQL ile |
| `DbSecurityIT` | 11 | Rol öznitelikleri (süper kullanıcı/BYPASSRLS yok), PUBLIC yetkisizliği, RLS okuma/yazma yalıtımı, kimliksiz bağlantı, app_customer / app_admin / reporting_readonly / data_loader yetki sınırları, SQL injection, hassas veri |
| `ReportsIT` | 7 | Q1–Q5 elle hesaplanmış beklenen değerlerle (ör. Q1 gelir payı 99,01 / 0,99; Q4 %75,00 ve 0,75 saat; Q5 %40,00), §4.5 view'ları, rapor API'si ve canlı EXPLAIN |
| `AuthIT` | 7 | Kayıt, BCrypt, tekrar e-posta, giriş, pasif hesap, 401/403 rol erişimi, sahte token, profil ve parola |
| `CatalogIT` | 5 | Ad/genre/publisher/developer/fiyat/tür filtreleri, sıralama, sayfalama, pasif ürün, detay, puan ve yorumlar |
| `LibraryReviewWishlistIT` | 5 | Oturum başlat/bitir/kaydet, DLC ve yabancı kayıt reddi, yorum ekleme/güncelleme, yorum kısıtları (API + DB), istek listesi |
| `AdminIT` | 4 | Ana oyun ve DLC oluşturma, geçersiz girişler, DLC→DLC reddi (BR05) ve rollback, güncelleme/pasife alma, referans veri CRUD ve RESTRICT, tür filtresi |

Test başına açıklama, sonuç ve süre: `docs/kanit/backend_testleri.md` (Surefire XML'den otomatik üretilir).

## 3. Geliştirme sırasında görülen başarısızlıklar ve düzeltmeler

Aşağıdakiler gerçek koşularda görülmüş ve düzeltilmiştir; nihai koşu yukarıdaki sonuçtur.

| Ne zaman | Görülen | Neden | Düzeltme |
|---|---|---|---|
| İlk tam test koşusu | 74 testten 3'ü başarısız | Test tarafı: (1) `'SHIPPED'` ekleme durumu CHECK'ten önce `BEFORE INSERT` trigger'ına takıldı; (2) `"char"` birleştirme belirsizliği; (3) `(order_id, line_no)` UNIQUE, hedeflenen deferred trigger'dan önce devreye girdi | Testler, hedefledikleri kuralı tek başına tetikleyecek şekilde düzeltildi; UNIQUE durumu ayrı bir doğrulama olarak eklendi. Uygulama kodu değişmedi |
| İkinci koşu | 1 test başarısız | Ödeme `status` CHECK testi tamamlanmış siparişte trigger'a takıldı | Test PENDING siparişe yöneltildi |
| İlk tam veri yüklemesi | `ERROR: COPY FROM not supported with row-level security` | PostgreSQL, RLS'li tabloya RLS'e tabi rolle COPY'ye izin vermiyor | TEMP hazırlık tablosu + `INSERT … SELECT` (Tasarım notları Ç16). Hata anında hiçbir satır commit edilmedi, yetkiler geri alındı |
| İkinci yükleme, doğrulama adımı | `could not resize shared memory segment … No space left on device` | Docker'ın varsayılan 64 MB `/dev/shm`'si paralel hash join için yetersiz | `docker-compose.yml`: `shm_size: 1gb` |
| İlk indeks deneyi | `psql: … No such file or directory` | Isınma koşusu ana makine yolunu konteyner içinde aradı | Isınma da stdin ile; `trap` sayesinde kaldırılan indeks geri oluşturulmuştu (doğrulandı) |
| İlk ekran görüntüleri / duman testi | `/magaza` adresi 404 sayfası gösterdi; duman testi bunu yakalayamadı | Katalog rotası `/`; duman testi yalnızca HTTP 200'e bakıyordu (nginx her yolda index.html döner) | Betikler `/` kullanır; duman testi artık index.html içeriğini ve JS paketinin yüklenebildiğini doğrular |

## 4. Kapsanmayanlar / sınırlar

- Frontend için tarayıcı tabanlı otomatik E2E test paketi (Playwright vb.) yoktur. Arayüzün gerçek backend ile
  çalıştığı; duman testi (aynı nginx kökeni ve `/api` yolu), ekran görüntüsü betiği (gerçek ödeme formu kullanılarak
  başarısız + başarılı ödeme) ve görüntülerin elle incelenmesiyle doğrulanmıştır.
- Yük/eşzamanlılık testi iki eşzamanlı ödeme senaryosuyla sınırlıdır; çok kullanıcılı yük testi yapılmamıştır.
