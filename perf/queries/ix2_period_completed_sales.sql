-- IX2 store_order(completed_at, order_id) WHERE status = 'COMPLETED' — Dönemsel tamamlanmış satışlar (1 haftalık gelir)
SELECT date_trunc('day', so.completed_at) AS gun,
       count(DISTINCT so.order_id)        AS siparis,
       sum(oi.unit_price_paid)            AS gelir
  FROM store_order so
  JOIN order_item oi ON oi.order_id = so.order_id
 WHERE so.status = 'COMPLETED'
   AND so.completed_at >= TIMESTAMPTZ '2026-03-02 00:00+03'
   AND so.completed_at <  TIMESTAMPTZ '2026-03-09 00:00+03'
 GROUP BY 1
 ORDER BY 1;
