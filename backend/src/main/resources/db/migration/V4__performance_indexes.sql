-- =====================================================================
-- OyunPort  V4: Rapor §8.2 indeks adayları
-- Bu beş ikincil indeks aynı zamanda ilgili FK sütunlarını destekler.
-- perf/run_index_experiments.sh her birini geçici olarak kaldırıp
-- EXPLAIN (ANALYZE, BUFFERS) ile öncesi/sonrası ölçer ve geri oluşturur.
-- PK/UQ indeksleri deneylerde kaldırılmaz.
-- =====================================================================

-- IX1  Kullanıcı sipariş geçmişi
CREATE INDEX ix_store_order_user_created ON store_order (user_id, created_at DESC);

-- IX2  Dönemsel tamamlanmış satışlar (kısmi indeks)
CREATE INDEX ix_store_order_completed ON store_order (completed_at, order_id) WHERE status = 'COMPLETED';

-- IX3  Ürün satışları ve JOIN
CREATE INDEX ix_order_item_product_order ON order_item (product_id, order_id);

-- IX4  Oynama geçmişi ve süre analizi
CREATE INDEX ix_play_session_library_started ON play_session (library_id, started_at);

-- IX5  Publisher kataloğu ve rapor JOIN
CREATE INDEX ix_product_publisher_release ON product (publisher_id, release_date);
