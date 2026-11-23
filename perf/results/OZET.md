# İndeks deneyleri — özet (otomatik üretildi: perf/summarize.py)

Değerler `perf/results/*_{without,with}.txt` içindeki gerçek `EXPLAIN (ANALYZE, BUFFERS)` çıktılarından ayrıştırılmıştır. Süreler ısınma koşusundan sonraki koşuların ortanca/min/max değeridir (sıcak önbellek).

Parametreler:
```
uid=11564  (en çok siparişi olan kullanıcı; sipariş sayısı: 134)
pid=3325  (satış adedine göre 201. ürün; kalem sayısı: 393)
pub=7  (en çok ürünü olan publisher; ürün sayısı: 38)
RUNS=5 (+1 ısınma koşusu, ölçüme dahil değil)
```

| Deney | İndekssiz ortanca (min–max) ms | İndeksli ortanca (min–max) ms | Oran | Plan maliyeti (önce → sonra) | Tahmini/gerçek satır (kök) | shared hit/read (önce → sonra) | Tarama türleri (önce → sonra) |
|---|---|---|---|---|---|---|---|
| IX1 store_order(user_id, created_at DESC) — kullanıcı sipariş geçmişi | 14.967 (12.467–16.991) | 0.065 (0.064–0.104) | 230.3× | 8575.5 → 44.6 | 20/20 → 20/20 | 6083/0 → 8/0 | Parallel Seq Scan on store_order → Index Scan using ix_store_order_user_created on store_order |
| IX2 store_order(completed_at, order_id) WHERE COMPLETED — 1 haftalık satış | 21.318 (20.412–43.302) | 29.186 (27.923–32.459) | 0.7× | 17279.1 → 9465.9 | 2217/8 → 2500/8 | 15531/603 → 4547/0 | Index Scan using uq_order_item_order_product on order_item; Parallel Seq Scan on store_order → Index Only Scan using ix_store_order_completed on store_order; Parallel Seq Scan on order_item |
| IX2 (ek deney, random_page_cost=1.1) — 1 haftalık satış | 22.293 (19.153–26.445) | 11.269 (11.069–11.849) | 2.0× | 11971.0 → 5354.5 | 2130/8 → 2074/8 | 16134/0 → 10111/0 | Index Scan using uq_order_item_order_product on order_item; Parallel Seq Scan on store_order → Index Only Scan using ix_store_order_completed on store_order; Index Scan using uq_order_item_order_product on order_item |
| IX3 order_item(product_id, order_id) — tek ürün satışları | 14.820 (14.008–16.284) | 2.962 (2.839–3.240) | 5.0× | 8929.9 → 6094.2 | 1/1 → 1/1 | 5991/0 → 1966/0 | Index Scan using pk_product on product; Index Scan using pk_store_order on store_order; Parallel Seq Scan on order_item → Bitmap Heap Scan on order_item; Bitmap Index Scan on ix_order_item_product_order; Index Scan using pk_product on product; Index Scan using pk_store_order on store_order |
| IX4 play_session(library_id, started_at) — kullanıcı oynama süreleri | 38.514 (37.055–39.815) | 1.680 (1.629–1.945) | 22.9× | 14123.0 → 10215.2 | 245/117 → 245/117 | 7962/0 → 1446/0 | Index Scan using uq_library_entry_user_product on library_entry; Parallel Seq Scan on play_session → Index Scan using ix_play_session_library_started on play_session; Index Scan using uq_library_entry_user_product on library_entry |
| IX4 (ikinci iş yükü) — Q4 tüm sahiplikler | 1386.239 (1358.646–1405.859) | 1220.323 (1186.958–1237.152) | 1.1× | 154005.1 → 138186.7 | 50/50 → 50/50 | 12532/0 → 1020025/0 | Seq Scan on base_game; Seq Scan on library_entry; Seq Scan on play_session; Seq Scan on product → Index Only Scan using pk_base_game on base_game; Index Scan using ix_play_session_library_started on play_session; Index Scan using pk_library_entry on library_entry; Seq Scan on product |
| IX5 product(publisher_id, release_date) — publisher kataloğu | 0.575 (0.327–0.587) | 0.215 (0.168–0.381) | 2.7× | 162.6 → 79.6 | 38/38 → 38/38 | 102/0 → 40/0 | Seq Scan on product → Bitmap Heap Scan on product; Bitmap Index Scan on ix_product_publisher_release |
| IX5 (ikinci iş yükü) — Q1 aylık publisher geliri | 592.253 (554.969–643.813) | 582.201 (575.330–596.219) | 1.0× | 427629.3 → 427629.3 | 538910/105 → 538910/105 | 5558/0 → 5558/0 | Index Only Scan using ix_store_order_completed on store_order; Seq Scan on order_item; Seq Scan on product; Seq Scan on publisher → Index Only Scan using ix_store_order_completed on store_order; Seq Scan on order_item; Seq Scan on product; Seq Scan on publisher |

Oran = indekssiz ortanca / indeksli ortanca (1'den büyükse indeks hızlandırmıştır; ≈1 ise planlayıcı indeksi kullanmamış veya fark ölçülemeyecek kadar küçüktür).
