-- IX3 order_item(product_id, order_id) — Tek ürünün tamamlanmış satışları (ürün satış raporu ve JOIN)
SELECT p.product_id, p.title, count(*) AS adet, sum(oi.unit_price_paid) AS gelir
  FROM order_item oi
  JOIN store_order so ON so.order_id = oi.order_id AND so.status = 'COMPLETED'
  JOIN product p      ON p.product_id = oi.product_id
 WHERE oi.product_id = :pid
 GROUP BY p.product_id, p.title;
