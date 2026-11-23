# İlerleme kaydı

Her satır gerçekten çalıştırılmış bir komut/test sonucuna dayanır. Tarih: 2026-11-23.

| # | Aşama | Durum | Kanıt |
|---|---|---|---|
| 1 | Ara rapor incelemesi, izlenebilirlik tablosu, çelişki analizi | Tamamlandı | `docs/IZLENEBILIRLIK.md`, `docs/TASARIM_NOTLARI.md` |
| 2 | PostgreSQL şeması (V1–V6 Flyway migration) | Tamamlandı | Geçici PG16 konteynerinde `db_admin` (süper kullanıcı değil) ile hatasız uygulandı; SQL düzeyi duman testi (T1/T2, negatif durumlar) beklenen hataları üretti |
| 3 | Backend (Spring Boot 3.5.16, JDBC + HikariCP, iki havuz) | Tamamlandı | `scripts/mvn.sh -DskipTests compile` başarılı |
| 4 | İlk hedef: şema + JDBC + uçtan uca satın alma | Tamamlandı | `CheckoutIT`: 17 test, 0 hata (Testcontainers PostgreSQL 16) |
| 5 | Backend entegrasyon testleri (8 sınıf) | Tamamlandı | `scripts/mvn.sh test`: 74 test, 0 hata, 0 başarısız (ilk tam koşuda 3 test-tarafı hatası bulundu ve düzeltildi; ayrıntı `docs/TEST_SONUCLARI.md`) |
| 6 | Frontend (React 18 + Vite + TS, 12 ekran) | Tamamlandı | Temiz `node:22-alpine` konteynerinde `npm ci`, 33 birim testi başarılı, `tsc` + `vite build` hatasız (`docs/kanit/frontend_test_build.txt`) |
| 7 | Frontend geri bildirimiyle backend düzeltmeleri | Tamamlandı | Sepet sınırı = sipariş sınırı (25), referans güncellemede gerçek ürün sayısı, admin ürün listesinde `type` filtresi; testleri eklendi, 74/74 başarılı |
| 8 | Docker Compose temiz kurulum | Tamamlandı | `docker compose down -v && docker compose up -d --build`: Flyway V1–V6 uygulandı, havuzlar `app_customer`/`app_admin` ile bağlandı, nginx SPA + `/api` |
| 9 | Sentetik veri üretimi ve yükleme | Tamamlandı | Üretim 16 s; yükleme 130 s (tüm trigger'lar açık); 17 iş kuralı denetimi 0 ihlal (`docs/kanit/veri_dogrulama.txt`). Bulunan sorunlar: RLS + COPY (Ç16), Docker `/dev/shm` |
| 10 | İndeks deneyleri ve Q1–Q5 planları | Tamamlandı | 5 indeks × 2 aşama × 5 koşu + 2 ikinci iş yükü + 1 ek deney (`random_page_cost`) + Q5 JIT ölçümü; `docs/PERFORMANS.md`, `perf/results/` |
| 11 | Uçtan uca duman testi ve gerçek arayüz ekranları | Tamamlandı | 22/22 PASS (`docs/kanit/e2e_smoke.txt`); 16 ekran (`docs/kanit/ekran/`) gözle incelendi |
| 12 | Dokümantasyon | Tamamlandı | README (kurulum), izlenebilirlik, tasarım notları, şema, normalizasyon, test sonuçları, performans |
