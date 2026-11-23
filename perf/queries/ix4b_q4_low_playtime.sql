-- IX4 ikinci iş yükü: Q4 (geniş sonuç kümesi; tüm sahiplikler ve tüm oturumlar taranır)
SELECT * FROM rpt_q4_low_playtime_games ORDER BY low_play_pct DESC, eligible_owners DESC LIMIT 50;
