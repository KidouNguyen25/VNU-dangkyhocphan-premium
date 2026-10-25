#!/bin/sh
# BẢN TẠM cho môi trường dev. Chạy sau tmp1_init.sql, một lần khi volume còn trống.
# Tạo role ứng dụng (DB_USER) chỉ có quyền đọc ghi dữ liệu, không có quyền đổi schema.

psql -v ON_ERROR_STOP=1 \
     -v dbname="$POSTGRES_DB" -v dbuser="$DB_USER" -v dbpass="$DB_PASSWORD" \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE :"dbuser" LOGIN PASSWORD :'dbpass';
GRANT CONNECT ON DATABASE :"dbname" TO :"dbuser";
GRANT USAGE ON SCHEMA public TO :"dbuser";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"dbuser";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"dbuser";
SQL
