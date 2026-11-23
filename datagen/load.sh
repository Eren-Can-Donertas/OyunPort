#!/usr/bin/env bash
# Sentetik veriyi Docker Compose'daki PostgreSQL'e COPY ile yükler ve doğrular.
#   Ön koşul: docker compose up -d   (backend Flyway migration'larını uygulamış olmalı)
#             python3 datagen/generate.py --scale full
#   Kullanım: datagen/load.sh
# Çıktılar:   docs/kanit/veri_yukleme_log.txt, docs/kanit/veri_dogrulama.txt
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [ -f .env ]; then set -a; . ./.env; set +a; fi
DB_ADMIN_PASSWORD="${DB_ADMIN_PASSWORD:-db_admin_dev_pw}"
LOADER_PW="${DATA_LOADER_DB_PASSWORD:-data_loader_dev_pw}"

if [ ! -f datagen/out/manifest.json ]; then
  echo "CSV dosyaları yok. Önce: python3 datagen/generate.py --scale full" >&2
  exit 1
fi

psql_as() {
  local user="$1" pw="$2"; shift 2
  docker compose exec -T -e PGPASSWORD="$pw" db \
    psql -X -h localhost -U "$user" -d oyunport -v ON_ERROR_STOP=1 "$@"
}

revoke_loader() {
  psql_as db_admin "$DB_ADMIN_PASSWORD" -q -c "REVOKE ALL ON ALL TABLES IN SCHEMA public FROM data_loader" \
    -c "REVOKE TEMPORARY ON DATABASE oyunport FROM data_loader" || true
}

mkdir -p docs/kanit
echo "== 1/4 Ön kontrol ve geçici yetkiler (db_admin)"
psql_as db_admin "$DB_ADMIN_PASSWORD" -q < datagen/sql/00_preflight_grant.sql

echo "== 2/4 COPY yükleme (data_loader) — trigger ve kısıtlar açık"
start=$(date +%s)
{
  echo "# Veri yükleme günlüğü — $(date -Iseconds)"
  echo "# manifest: $(tr -d '\n' < datagen/out/manifest.json)"
} > docs/kanit/veri_yukleme_log.txt
if ! psql_as data_loader "$LOADER_PW" < datagen/sql/10_load.sql 2>&1 | tee -a docs/kanit/veri_yukleme_log.txt; then
  echo "Yükleme BAŞARISIZ; data_loader yetkileri geri alınıyor." >&2
  revoke_loader
  exit 1
fi
end=$(date +%s)
echo "# Toplam yükleme süresi (duvar saati): $((end - start)) s" | tee -a docs/kanit/veri_yukleme_log.txt

echo "== 3/4 Sonlandırma: REVOKE, identity sayaçları, VACUUM ANALYZE (db_admin)"
psql_as db_admin "$DB_ADMIN_PASSWORD" -q < datagen/sql/20_finalize.sql

echo "== 4/4 Doğrulama (db_admin)"
psql_as db_admin "$DB_ADMIN_PASSWORD" < datagen/sql/30_validate.sql | tee docs/kanit/veri_dogrulama.txt
if grep -q "HATA" docs/kanit/veri_dogrulama.txt; then
  echo "Doğrulamada ihlal bulundu!" >&2
  exit 2
fi
echo "Tamamlandı."
