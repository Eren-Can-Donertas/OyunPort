#!/bin/sh
# PostgreSQL ilk başlatma betiği (docker-entrypoint-initdb.d).
# Süper kullanıcı yalnızca bu önyükleme adımında kullanılır:
#   * db_admin: şema sahibi, migration (Flyway) kullanıcısı; süper kullanıcı DEĞİLDİR,
#     yalnızca uygulama rollerini oluşturabilmek için CREATEROLE yetkisi vardır.
#   * oyunport veritabanı db_admin'e aittir.
# Uygulama rolleri (app_customer, app_admin, reporting_readonly, data_loader) Flyway
# migration'ı V5 tarafından oluşturulur.
set -eu

: "${DB_ADMIN_PASSWORD:?DB_ADMIN_PASSWORD tanımlı olmalı}"
: "${OYUNPORT_DB_NAME:=oyunport}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v dbpw="$DB_ADMIN_PASSWORD" -v dbname="$OYUNPORT_DB_NAME" <<'EOSQL'
CREATE ROLE db_admin LOGIN CREATEROLE NOSUPERUSER NOCREATEDB PASSWORD :'dbpw';
CREATE DATABASE :"dbname" OWNER db_admin ENCODING 'UTF8' TEMPLATE template0;
EOSQL

echo "db_admin rolü ve ${OYUNPORT_DB_NAME} veritabanı oluşturuldu."
