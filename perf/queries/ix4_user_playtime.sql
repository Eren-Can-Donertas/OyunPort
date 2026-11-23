-- IX4 play_session(library_id, started_at) — Kullanıcının oyun başına oynama süresi (Kütüphanem ekranı)
SELECT le.product_id,
       count(ps.session_id)                AS oturum,
       sum(ps.ended_at - ps.started_at)    AS toplam_sure,
       max(ps.started_at)                  AS son_oturum
  FROM library_entry le
  JOIN play_session ps ON ps.library_id = le.library_id
 WHERE le.user_id = :uid
 GROUP BY le.product_id;
