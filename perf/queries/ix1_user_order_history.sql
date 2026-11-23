-- IX1 store_order(user_id, created_at DESC) — Kullanıcı sipariş geçmişi (Siparişlerim ekranı)
SELECT so.order_id, so.status, so.created_at, so.completed_at
  FROM store_order so
 WHERE so.user_id = :uid
 ORDER BY so.created_at DESC
 LIMIT 20;
