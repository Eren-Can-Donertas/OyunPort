-- IX5 product(publisher_id, release_date) — Publisher kataloğu (çıkış tarihine göre)
SELECT p.product_id, p.title, p.release_date, p.list_price
  FROM product p
 WHERE p.publisher_id = :pub
 ORDER BY p.release_date DESC;
