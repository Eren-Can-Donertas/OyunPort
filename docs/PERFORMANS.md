# Performans deneyleri (rapor §8.2 ve §8.3)

Bu belgedeki bütün sayılar gerçekten çalıştırılmış `EXPLAIN (ANALYZE, BUFFERS)` çıktılarından alınmıştır.
Ham çıktılar `perf/results/`, otomatik özet `perf/results/OZET.md` (`python3 perf/summarize.py`).

## 1. Ortam ve yöntem

- **Ortam** (`perf/results/ortam.txt`): Linux 7.0, 16 çekirdek, 15 GB RAM, NVMe disk; PostgreSQL 16.14 (Docker, alpine);
  `shared_buffers = 256MB`, `work_mem = 16MB`, `max_parallel_workers_per_gather = 2`, `random_page_cost = 4` (varsayılan), `jit = on` (varsayılan).
- **Veri**: `datagen/generate.py --scale full` (seed 372) ile yüklenen veri: 300.000 sipariş, 599.878 kalem,
  539.416 kütüphane kaydı, 1.000.000 oynama oturumu (`docs/kanit/veri_dogrulama.txt`).
- **Yöntem** (`perf/run_index_experiments.sh`): her deney için aynı sorgu ve aynı parametrelerle
  1) ilgili ikincil indeks `DROP` → `ANALYZE` → 1 ısınma koşusu → 5 ölçüm; 2) indeks `CREATE` → `ANALYZE` → 1 ısınma → 5 ölçüm.
  PK/UQ indeksleri kaldırılmadı. Ölçümler **sıcak önbellek** koşullarındadır (ısınmadan sonra okuma çoğunlukla `shared hit`);
  soğuk disk okuması ölçülmemiştir. Parametreler veriden deterministik seçildi (`perf/results/params.txt`):
  en çok siparişi olan kullanıcı (uid=11564, 134 sipariş), satış sıralamasında 201. ürün (pid=3325, 393 kalem),
  en çok ürünü olan publisher (pub=7, 38 ürün).

## 2. Sonuçlar

Süreler 5 koşunun ortancasıdır (parantezde min–max). Oran = indekssiz / indeksli.

| # | İndeks | İş yükü | İndekssiz (ms) | İndeksli (ms) | Oran | Plan değişimi |
|---|---|---|---|---|---|---|
| IX1 | `store_order(user_id, created_at DESC)` | Kullanıcının son 20 siparişi | 14,967 (12,467–16,991) | 0,065 (0,064–0,104) | **230×** | Parallel Seq Scan (6.083 buffer) → Index Scan, sıralama yok (8 buffer) |
| IX2 | `store_order(completed_at, order_id) WHERE status='COMPLETED'` | 1 haftalık günlük gelir | 21,318 (20,412–43,302) | 29,186 (27,923–32,459) | **0,7× (yavaşladı)** | store_order için Index Only Scan, fakat order_item için nested-loop yerine Parallel Seq Scan + Hash Join seçildi |
| IX2-ek | aynı, oturumda `random_page_cost = 1.1` | aynı | 22,293 (19,153–26,445) | 11,269 (11,069–11,849) | **2,0×** | Index Only Scan + order_item'da `uq_order_item_order_product` ile nested loop |
| IX3 | `order_item(product_id, order_id)` | Tek ürünün tamamlanmış satışları | 14,820 (14,008–16,284) | 2,962 (2,839–3,240) | **5,0×** | Parallel Seq Scan (5.991 buffer) → Bitmap Index/Heap Scan (1.966 buffer) |
| IX4 | `play_session(library_id, started_at)` | Bir kullanıcının oyun başına oynama süresi | 38,514 (37,055–39,815) | 1,680 (1,629–1,945) | **22,9×** | 1 M satırlık Parallel Seq Scan → kayıt başına Index Scan |
| IX4-b | aynı | Q4 (tüm sahiplikler) | 1.386,2 (1.358,6–1.405,9) | 1.220,3 (1.187,0–1.237,2) | 1,1× | Hash Join + Seq Scan → Merge Left Join + Index Scan; shared hit 12.532 → 1.020.025 |
| IX5 | `product(publisher_id, release_date)` | Publisher kataloğu (38 ürün) | 0,575 (0,327–0,587) | 0,215 (0,168–0,381) | 2,7× | Seq Scan (102 buffer) → Bitmap Index Scan (40 buffer) |
| IX5-b | aynı | Q1 (publisher JOIN'li aylık gelir) | 592,3 (555,0–643,8) | 582,2 (575,3–596,2) | 1,0× | Plan aynı; product 5.000 satırla Seq Scan + Hash Join |

İndeks oluşturma maliyeti (`perf/results/*_create_index.txt`): IX1 91,8 ms / 9,0 MB · IX2 71,7 ms / 8,1 MB ·
IX3 164,9 ms / 18 MB · IX4 265,1 ms / 30 MB · IX5 8,7 ms / 176 kB.

## 3. Yorum

1. **Seçici, satır-odaklı erişimlerde indeksler belirgin hızlandırdı**: IX1 (230×), IX4 (22,9×), IX3 (5,0×).
   Kök düğümde tahmini/gerçek satır IX1'de 20/20, IX3'te 1/1; IX4'te grup sayısı 245 tahmin edilip 117 gerçekleşti
   (aynı mertebe). Buna karşılık Q1'in kökünde tahmin 538.910, gerçek 105 satırdır: planlayıcı pencere fonksiyonu
   üzerindeki `revenue_rank <= 3` filtresinin seçiciliğini tahmin edemez; plan seçimi bundan etkilenmez çünkü filtre en üstte uygulanır.
   Bu sorgular uygulamanın Siparişlerim, Kütüphanem ve ürün satış ekranlarına karşılık gelir; indeksler bu nedenle şemada tutulmuştur.
2. **IX2 varsayılan ayarlarda sorguyu yavaşlattı.** İndeksle planlayıcının maliyet tahmini düştü (17.279 → 9.466),
   ancak `order_item` için 2.300 ayrı indeks erişimi yerine 600 bin satırlık paralel taramayı seçti ve gerçek süre arttı.
   Bunun nedeni varsayılan `random_page_cost = 4` değerinin (dönen disk varsayımı) rastgele erişimi pahalı göstermesidir.
   Hipotezi sınamak için aynı deney yalnızca oturumda `random_page_cost = 1.1` ile tekrarlandı: indeksli plan nested loop'a
   döndü ve **2,0× hızlandı** (22,3 → 11,3 ms). Sonuç: indeks doğru, sorunu maliyet parametresi. SSD üzerindeki kurulum için
   `random_page_cost ≈ 1.1` önerilir; ancak sunucu ayarı bu projede **değiştirilmedi** (diğer ölçümler varsayılan ayarlarla karşılaştırılabilir kalsın diye).
   Ayrıca IX2, Q1'de (IX5-b satırı) `store_order` erişimi için Index Only Scan olarak kullanılmaktadır.
3. **Geniş sonuç kümeli analitik sorgularda sequential scan doğru seçimdir** (rapor §8.2'nin öngördüğü gibi).
   Q4 tüm sahiplikleri ve 1 M oturumu okuduğu için IX4 yalnızca 1,1× fark yarattı; indeksli plan 80 kat fazla buffer'a
   dokunmasına rağmen sıralı birleştirme (Merge Join) sayesinde biraz daha hızlıdır. Q1'de 5.000 satırlık `product`
   tablosu hash join ile tek seferde okunduğundan IX5 kullanılmadı (1,0×).
4. **Küçük tablo**: IX5'in kendi iş yükünde mutlak kazanç 0,36 ms'dir; 5.000 satırlık tabloda fark pratikte önemsizdir.
   İndeks, publisher FK'sinin RESTRICT kontrolünü de desteklediği için tutulmuştur.

## 4. Q1–Q5 nihai planları (tüm indeksler mevcut, varsayılan ayarlar)

`perf/results/q1_plan.txt` … `q5_plan.txt` (ilk 5 satır + tam plan).

| Sorgu | Satır | Çalışma süresi | Planın ana hatları |
|---|---|---|---|
| Q1 aylık ilk 3 publisher | 105 | 576,1 ms (JIT 17,0 ms) | order_item Seq Scan + `ix_store_order_completed` Index Only Scan, Hash Join ×3, HashAggregate, WindowAgg (DENSE_RANK) |
| Q2 tür içinde en çok satan | 90 | 439,0 ms | Hash Join (918.706 genre-kalem satırı), HashAggregate (7.741 grup), Sort + WindowAgg (RANK) |
| Q3 ortalama üstü müşteriler | 13.588 | 703,4 ms (JIT 30,2 ms) | Ortalama alt sorgusu için Parallel Hash Join; ana sorgu Hash Join + Sort + GroupAggregate |
| Q4 az oynanan ana oyunlar | 3.835 | 1.250,0 ms (JIT 11,1 ms) | library_entry ⋈ base_game Nested Loop, play_session ile Merge Left Join (`ix_play_session_library_started`), GroupAggregate |
| Q5 DLC dönüşümü | 408 | 864,1 ms (**JIT 427,1 ms**) | Merge/Nested Loop; sahip başına EXISTS alt planı `uq_library_entry_user_product` ve `ix_dlc_base_game` ile |

**JIT gözlemi:** Q5'te JIT derlemesi (inlining + optimization) çalışma süresinin yaklaşık yarısıdır. Ek ölçüm
(`perf/results/q5_jit_on.txt`, `q5_jit_off.txt`, 5'er koşu): ortanca **831,6 ms (jit=on)** ve **442,9 ms (jit=off)**.
Bu boyuttaki raporlama sorgularında JIT kazanç sağlamıyor; `jit = off` veya daha yüksek `jit_above_cost` önerilir.
Bu ayar da projede değiştirilmemiştir (öneri olarak belgelenmiştir).

## 5. Sınırlar

- Ölçümler tek makinede, sıcak önbellekte ve tek oturumda yapılmıştır; eşzamanlı yük altında ölçüm yapılmamıştır.
- Süreler Docker içindeki PostgreSQL'e aittir; aynı makinede başka iş yükü çalışmazken alınmıştır.
- Planlayıcı kararları veri dağılımına bağlıdır; farklı seed veya hacimle oranlar değişebilir.
