# OyunPort — Dijital Oyun Mağazası (BİL372 Final Projesi)

TOBB ETÜ BİL372 Veritabanı Sistemleri dersi final projesi. Ara rapordaki (docs/BIL372_OyunPort_Ara_Rapor.pdf)
kavramsal/mantıksal tasarımın çalışan, test edilen uygulamasıdır.

**Mimari:** React (Vite, TypeScript) → nginx `/api` → Spring Boot 3.5 (REST, PostgreSQL JDBC + HikariCP, ORM yok)
→ PostgreSQL 16. Şema Flyway SQL migration'larıyla kurulur. Sentetik veri Python ile üretilip `COPY` ile yüklenir.

```
 Tarayıcı ──► web (nginx:80, React SPA) ──/api──► backend (Spring Boot:8080)
                                                     ├─ customer-pool  (DB rolü app_customer, RLS)
                                                     ├─ admin-pool     (DB rolü app_admin)
                                                     └─ Flyway         (DB rolü db_admin, yalnızca migration)
                                                              ▼
                                                   db (PostgreSQL 16, oyunport veritabanı)
```

## 1. Gereksinimler

| Araç | Neden | Sürüm |
|---|---|---|
| Docker + Docker Compose v2 | Tüm servisler (derleme dahil) | Docker 24+ (test edilen: 29.7, Compose v5.4) |
| Python 3.10+ | Sentetik veri üretimi, özet betikleri (yalnızca standart kütüphane) | test edilen: 3.12 |

Ana makinede Java, Maven veya Node.js **gerekmez**: backend ve frontend Docker içinde derlenir; backend testleri
`scripts/mvn.sh` ile Maven konteynerinde çalışır.

## 2. Kurulum ve çalıştırma (temiz ortam)

```bash
cp .env.example .env                 # isteğe bağlı; yoksa varsayılan geliştirme değerleri kullanılır
docker compose up -d --build         # db -> backend (Flyway V1–V6) -> web
curl http://127.0.0.1:8080/api/health   # {"status":"UP","customerPool":{"dbRole":"app_customer",...},...}
```

Uygulama: **http://127.0.0.1:8080** · Backend doğrudan: 127.0.0.1:8081 · PostgreSQL: 127.0.0.1:55433
(portlar `.env` ile değiştirilebilir).

Seed yönetici hesabı (V6): `admin@oyunport.local` / `Admin123!` (ADMIN + CUSTOMER rolleri). Yalnızca geliştirme/demo içindir.

## 3. Sentetik veri (rapor §8.1)

```bash
python3 datagen/generate.py --scale full    # ≈3,1 M satır CSV -> datagen/out/  (~16 s)
datagen/load.sh                             # COPY + kontrollü batch yükleme, yetki geri alma, doğrulama
```

- `--scale small` hızlı demo verisi üretir. Yükleme yalnızca **boş** veritabanına yapılır; sıfırlamak için
  `docker compose down -v && docker compose up -d`.
- Tüm sentetik kullanıcıların parolası `Oyun123!`; demo müşteri: `demo@oyunport.test`.
- Yükleme günlüğü ve doğrulama çıktısı: `docs/kanit/veri_yukleme_log.txt`, `docs/kanit/veri_dogrulama.txt`.

## 4. Testler

```bash
scripts/mvn.sh test                 # backend: 8 entegrasyon test sınıfı, gerçek PostgreSQL 16 (Testcontainers)
python3 scripts/e2e_smoke.py        # çalışan yığına karşı uçtan uca duman testi (nginx -> backend -> DB)
docker run --rm -v "$PWD/frontend":/app -w /app node:22-alpine sh -c "npm ci && npm test"   # frontend birim testleri
```

Gerçek arayüzün ekran görüntüleri (Chrome gerekir): `SHOWCASE_ID=<ana_oyun_id> node scripts/screenshots.mjs` → `docs/kanit/ekran/`.

Sonuçlar ve sayılar: `docs/TEST_SONUCLARI.md`.

## 5. Performans deneyleri (rapor §8.2)

```bash
perf/run_index_experiments.sh       # 5 indeks: önce indekssiz, sonra indeksli EXPLAIN (ANALYZE, BUFFERS) × 5
python3 perf/summarize.py           # perf/results/OZET.md
```

Ham planlar `perf/results/*.txt`; yorum `docs/PERFORMANS.md`.

## 6. Depo yapısı

| Yol | İçerik |
|---|---|
| `backend/src/main/resources/db/migration/` | V1 şema · V2 trigger'lar · V3 view'lar ve Q1–Q5 · V4 indeksler · V5 roller/yetkiler/RLS · V6 seed |
| `backend/src/main/java/tr/edu/etu/oyunport/` | `db/` JDBC transaction katmanı · `order/` T1/T2 satın alma · `catalog/`, `cart/`, `library/`, `review/`, `wishlist/`, `profile/`, `admin/`, `auth/` |
| `backend/src/test/java/` | Entegrasyon testleri (`*IT.java`) |
| `frontend/` | React uygulaması (Vite + TypeScript), nginx yapılandırması |
| `db/init/` | PostgreSQL önyükleme: `db_admin` rolü ve veritabanı |
| `datagen/` | Sentetik veri üreticisi, yükleme ve doğrulama SQL'leri |
| `perf/` | İndeks deney betikleri, sorgular ve ham sonuçlar |
| `scripts/` | `mvn.sh` (Docker içinde Maven), `e2e_smoke.py` |
| `docs/` | Ara rapor, izlenebilirlik, tasarım notları, şema, normalizasyon, testler, performans, kanıtlar |

## 7. Belgeler

- `docs/IZLENEBILIRLIK.md` — FR01–FR12 ve BR01–BR09 → veritabanı nesnesi, kod, ekran, otomatik test
- `docs/TASARIM_NOTLARI.md` — ara rapordaki çelişkiler, kararlar, varsayımlar
- `docs/SEMA.md` — EER ve ilişkisel şema diyagramları, trigger özeti
- `docs/NORMALIZASYON.md` — tablo bazında aday anahtarlar, FD'ler, BCNF
- `docs/TEST_SONUCLARI.md` — çalıştırılan testler ve sonuçları
- `docs/PERFORMANS.md` — indeks deneyleri ve Q1–Q5 planlarının yorumu
- `docs/ILERLEME.md` — aşama aşama ilerleme kaydı

## 8. Kapsam dışı (rapor §1.2)

Gerçek ödeme/banka entegrasyonu ve kart verisi, oyun dosyası dağıtımı, launcher, DRM, arkadaşlık/sohbet,
iade, hediye, abonelik ve çoklu para birimi uygulanmamıştır. Ödeme yerel bir simülasyondur.

## 9. Sorun giderme

- **Port çakışması:** `.env` içinde `WEB_HOST_PORT`, `BACKEND_HOST_PORT`, `DB_HOST_PORT` değiştirin.
- **`could not resize shared memory segment`:** PostgreSQL konteynerinin `/dev/shm` alanı küçük; `docker-compose.yml` içinde `shm_size: 1gb` tanımlıdır.
- **Yavaş Maven indirmesi:** backend imajı Maven deposunu BuildKit önbelleğinde tutar; ilk derlemeden sonra bağımlılıklar yeniden indirilmez.
- **Veriyi sıfırlama:** `docker compose down -v` (PostgreSQL volume'u silinir; sonraki `up` migration'ları yeniden uygular).
