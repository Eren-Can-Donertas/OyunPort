#!/usr/bin/env bash
# Rapor §8.2 indeks deneyleri: her aday için aynı veri ve filtrelerle önce ikincil indeks
# OLMADAN, sonra indeksle EXPLAIN (ANALYZE, BUFFERS) ölçümü. PK/UQ indeksleri kaldırılmaz.
# Her aşamada ANALYZE çalıştırılır, 1 ısınma koşusundan sonra RUNS kez ölçülür.
# Hata olsa bile çıkışta tüm indeksler yeniden oluşturulur (trap).
#
#   Ön koşul: yüklenmiş veri (datagen/load.sh)
#   Kullanım: perf/run_index_experiments.sh            (RUNS=5 varsayılan)
#   Çıktılar: perf/results/<deney>_{without,with}.txt, perf/results/params.txt, perf/results/ortam.txt
#             ardından: python3 perf/summarize.py  -> perf/results/OZET.md
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [ -f .env ]; then set -a; . ./.env; set +a; fi
DB_ADMIN_PASSWORD="${DB_ADMIN_PASSWORD:-db_admin_dev_pw}"
RUNS="${RUNS:-5}"
OUT=perf/results
mkdir -p "$OUT"

PGOPTS=""
psql_admin() {
  docker compose exec -T -e PGPASSWORD="$DB_ADMIN_PASSWORD" -e PGOPTIONS="$PGOPTS" db \
    psql -X -q -h localhost -U db_admin -d oyunport -v ON_ERROR_STOP=1 "$@"
}

declare -A DDL=(
  [ix_store_order_user_created]="CREATE INDEX IF NOT EXISTS ix_store_order_user_created ON store_order (user_id, created_at DESC)"
  [ix_store_order_completed]="CREATE INDEX IF NOT EXISTS ix_store_order_completed ON store_order (completed_at, order_id) WHERE status = 'COMPLETED'"
  [ix_order_item_product_order]="CREATE INDEX IF NOT EXISTS ix_order_item_product_order ON order_item (product_id, order_id)"
  [ix_play_session_library_started]="CREATE INDEX IF NOT EXISTS ix_play_session_library_started ON play_session (library_id, started_at)"
  [ix_product_publisher_release]="CREATE INDEX IF NOT EXISTS ix_product_publisher_release ON product (publisher_id, release_date)"
)
declare -A TABLE_OF=(
  [ix_store_order_user_created]=store_order [ix_store_order_completed]=store_order
  [ix_order_item_product_order]=order_item [ix_play_session_library_started]=play_session
  [ix_product_publisher_release]=product
)

restore_all() {
  for ix in "${!DDL[@]}"; do psql_admin -c "${DDL[$ix]}" >/dev/null 2>&1 || true; done
}
trap restore_all EXIT

# --- Parametreler veriden deterministik seçilir
P_UID=$(psql_admin -At -c "SELECT user_id FROM store_order GROUP BY user_id ORDER BY count(*) DESC, user_id LIMIT 1")
P_PID=$(psql_admin -At -c "SELECT product_id FROM order_item GROUP BY product_id ORDER BY count(*) DESC, product_id OFFSET 200 LIMIT 1")
P_PUB=$(psql_admin -At -c "SELECT publisher_id FROM product GROUP BY publisher_id ORDER BY count(*) DESC, publisher_id LIMIT 1")
{
  echo "uid=$P_UID  (en çok siparişi olan kullanıcı; sipariş sayısı: $(psql_admin -At -c "SELECT count(*) FROM store_order WHERE user_id = $P_UID"))"
  echo "pid=$P_PID  (satış adedine göre 201. ürün; kalem sayısı: $(psql_admin -At -c "SELECT count(*) FROM order_item WHERE product_id = $P_PID"))"
  echo "pub=$P_PUB  (en çok ürünü olan publisher; ürün sayısı: $(psql_admin -At -c "SELECT count(*) FROM product WHERE publisher_id = $P_PUB"))"
  echo "RUNS=$RUNS (+1 ısınma koşusu, ölçüme dahil değil)"
} | tee "$OUT/params.txt"

{
  echo "Tarih: $(date -Iseconds)"
  echo "Ana makine: $(uname -sr), CPU çekirdek: $(nproc), RAM: $(free -g | awk '/Mem:/{print $2}') GB"
  psql_admin -At -c "SELECT 'PostgreSQL: ' || version()"
  psql_admin -At -c "SELECT name || ' = ' || setting || coalesce(' ' || unit, '') FROM pg_settings WHERE name IN ('shared_buffers','work_mem','effective_cache_size','random_page_cost','max_parallel_workers_per_gather','jit') ORDER BY name"
  psql_admin -At -c "SELECT relname || ': ' || n_live_tup || ' satır, ' || pg_size_pretty(pg_total_relation_size(relid)) FROM pg_stat_user_tables WHERE relname IN ('store_order','order_item','play_session','library_entry','product') ORDER BY relname"
} | tee "$OUT/ortam.txt"

run_case() {
  local name="$1" ix="$2" qfile="$3"
  PGOPTS="${4:-}"
  local table="${TABLE_OF[$ix]}"
  for phase in without with; do
    local f="$OUT/${name}_${phase}.txt"
    if [ "$phase" = without ]; then
      psql_admin -c "DROP INDEX IF EXISTS $ix" -c "ANALYZE $table"
    else
      { echo "-- CREATE INDEX süresi ve boyutu"; psql_admin -c '\timing on' -c "${DDL[$ix]}" -c "ANALYZE $table" \
          -c "SELECT pg_size_pretty(pg_relation_size('$ix')) AS indeks_boyutu"; } > "$OUT/${name}_create_index.txt" 2>&1
    fi
    echo "# deney=$name indeks=$ix aşama=$phase  tarih=$(date -Iseconds)" > "$f"
    echo "# sorgu dosyası: $qfile  oturum ayarı (PGOPTIONS): ${PGOPTS:-yok}" >> "$f"
    psql_admin -v uid="$P_UID" -v pid="$P_PID" -v pub="$P_PUB" < "$qfile" >/dev/null    # ısınma
    for i in $(seq 1 "$RUNS"); do
      echo "## koşu $i" >> "$f"
      { echo "EXPLAIN (ANALYZE, BUFFERS)"; cat "$qfile"; } \
        | psql_admin -v uid="$P_UID" -v pid="$P_PID" -v pub="$P_PUB" >> "$f"
    done
    echo "  $name [$phase] tamam"
  done
}

echo "== Deneyler başlıyor"
run_case ix1_user_order_history      ix_store_order_user_created     perf/queries/ix1_user_order_history.sql
run_case ix2_period_completed_sales  ix_store_order_completed        perf/queries/ix2_period_completed_sales.sql
run_case ix3_product_sales           ix_order_item_product_order     perf/queries/ix3_product_sales.sql
run_case ix4_user_playtime           ix_play_session_library_started perf/queries/ix4_user_playtime.sql
run_case ix4b_q4_low_playtime        ix_play_session_library_started perf/queries/ix4b_q4_low_playtime.sql
run_case ix5_publisher_catalog       ix_product_publisher_release    perf/queries/ix5_publisher_catalog.sql
run_case ix5b_q1_monthly_publishers  ix_product_publisher_release    perf/queries/ix5b_q1_monthly_publishers.sql
# Ek deney: IX2'de indeksli plan yavaşladı; SSD için önerilen random_page_cost = 1.1 ile aynı ölçüm
run_case ix2r_period_sales_rpc11     ix_store_order_completed        perf/queries/ix2_period_completed_sales.sql "-c random_page_cost=1.1"
PGOPTS=""

echo "== Q1–Q5 nihai planları (tüm indeksler mevcut, varsayılan ayarlar)"
PGOPTS=""
restore_all
for q in q1:rpt_q1_monthly_top_publishers q2:rpt_q2_genre_top_sellers q3:rpt_q3_above_average_customers \
         q4:rpt_q4_low_playtime_games q5:rpt_q5_dlc_conversion; do
  key="${q%%:*}"; view="${q#*:}"
  {
    echo "# $key = $view  tarih=$(date -Iseconds)"
    echo "## satır sayısı: $(psql_admin -At -c "SELECT count(*) FROM $view")"
    echo "## ilk 5 satır"
    psql_admin -c "SELECT * FROM $view LIMIT 5"
    echo "## EXPLAIN (ANALYZE, BUFFERS)"
    psql_admin -c "EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM $view"
  } > "$OUT/${key}_plan.txt"
  echo "  $key planı kaydedildi"
done
echo "Bitti. Özet için: python3 perf/summarize.py"
