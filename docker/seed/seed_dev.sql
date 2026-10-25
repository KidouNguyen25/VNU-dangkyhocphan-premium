-- Dữ liệu mẫu cho môi trường dev và thử tải. KHÔNG tự chạy cùng docker compose.
-- Chạy sau khi schema đã được tạo (docker/initdb), bằng tài khoản chủ DB (POSTGRES_USER).
--
-- Cách chạy (mật khẩu dev truyền qua biến pw, không lưu trong repo):
--   docker compose exec -T postgres psql -U <POSTGRES_USER> -d <POSTGRES_DB> \
--       -v pw='<mat-khau-dev>' [-v students=1000] [-v period_status=OPEN] -f - < docker/seed/seed_dev.sql
--
-- Tạo: 1 tài khoản admin (code 'admin') và `students` sinh viên (SV00001, SV00002, ...)
-- cùng mật khẩu pw; 8 học phần; 12 lớp; đợt đăng ký 2026-1 (credit_limit 20).
-- Chạy lại được: bỏ qua dòng đã có, làm mới mốc giờ của đợt. Muốn xóa sạch: docker compose down -v.
-- Lưu ý: khi đợt ở trạng thái OPEN, đăng nhập admin bị khóa (ADMIN_LOGIN_LOCKED).
-- Dùng -v period_status=CLOSED để đăng nhập admin.
--
-- Các lớp được chọn để thử từng luật:
--   lớp 11 sĩ số 2 (SECTION_FULL); lớp 1 và 2 cùng INT3001 (COURSE_ALREADY_ENROLLED);
--   lớp 2 và 8 cùng ca Thứ Năm ca 1 (SESSION_CONFLICT); lớp 9 và 10 là học phần MAT1001 có 3 buổi.
\set ON_ERROR_STOP on
\encoding UTF8

\if :{?pw}
\else
    DO $$ BEGIN RAISE EXCEPTION 'Thiếu mật khẩu dev: thêm -v pw=<mat-khau>'; END $$;
\endif
\if :{?students}
\else
    \set students 1000
\endif
\if :{?period_status}
\else
    \set period_status OPEN
\endif

CREATE EXTENSION IF NOT EXISTS pgcrypto;
SELECT crypt(:'pw', gen_salt('bf', 10)) AS hash \gset

INSERT INTO account (code, full_name, password_hash, role)
VALUES ('admin', 'Quản trị', :'hash', 'ADMIN')
ON CONFLICT (code) DO NOTHING;

INSERT INTO account (code, full_name, password_hash, role)
SELECT 'SV' || lpad(g::text, 5, '0'), 'Sinh viên ' || lpad(g::text, 5, '0'), :'hash', 'STUDENT'
FROM generate_series(1, :students) g
ON CONFLICT (code) DO NOTHING;

INSERT INTO course (code, name, credits, prerequisite) VALUES
    ('INT3001', 'Kiến trúc phần mềm', 3, 'INT3005'),
    ('INT3002', 'Cơ sở dữ liệu', 3, ''),
    ('INT3003', 'Mạng máy tính', 3, ''),
    ('INT3004', 'Hệ điều hành', 3, ''),
    ('INT3005', 'Lập trình hướng đối tượng', 3, ''),
    ('INT3006', 'An toàn thông tin', 3, 'INT3003'),
    ('MAT1001', 'Giải tích 1', 3, ''),
    ('ENG1001', 'Tiếng Anh cơ bản', 2, '')
ON CONFLICT (code) DO NOTHING;

INSERT INTO registration_period (term, login_opens_at, opens_at, closes_at, status, credit_limit)
VALUES ('2026-1', now() - interval '1 hour', now() - interval '30 minutes', now() + interval '7 days',
        :'period_status'::registration_status, 20)
ON CONFLICT (term) DO UPDATE SET
    login_opens_at = EXCLUDED.login_opens_at, opens_at = EXCLUDED.opens_at,
    closes_at = EXCLUDED.closes_at, status = EXCLUDED.status, credit_limit = EXCLUDED.credit_limit;

-- session là bitmask 28 bit: bit (i - 1) với i = (thứ - 1) * 4 + ca trong ngày (ca 1 đến 4).
INSERT INTO section (id, course_code, term, lecturer, capacity, session) OVERRIDING SYSTEM VALUE VALUES
    (1,  'INT3001', '2026-1', 'Nguyễn Văn An',   120, (1 << 0) | (1 << 8)),            -- T2 ca1, T4 ca1
    (2,  'INT3001', '2026-1', 'Trần Thị Bình',   120, (1 << 4) | (1 << 12)),           -- T3 ca1, T5 ca1
    (3,  'INT3002', '2026-1', 'Lê Văn Cường',    100, (1 << 1) | (1 << 9)),            -- T2 ca2, T4 ca2
    (4,  'INT3002', '2026-1', 'Phạm Thị Dung',   100, (1 << 6) | (1 << 14)),           -- T3 ca3, T5 ca3
    (5,  'INT3003', '2026-1', 'Hoàng Văn Em',     80, (1 << 2) | (1 << 10)),           -- T2 ca3, T4 ca3
    (6,  'INT3004', '2026-1', 'Vũ Thị Giang',     80, (1 << 5) | (1 << 17)),           -- T3 ca2, T6 ca2
    (7,  'INT3005', '2026-1', 'Đặng Văn Hải',     60, (1 << 3) | (1 << 11)),           -- T2 ca4, T4 ca4
    (8,  'INT3006', '2026-1', 'Bùi Thị Lan',      60, (1 << 12) | (1 << 16)),          -- T5 ca1, T6 ca1
    (9,  'MAT1001', '2026-1', 'Ngô Văn Minh',    150, (1 << 7) | (1 << 15) | (1 << 19)), -- T3 ca4, T5 ca4, T6 ca4
    (10, 'MAT1001', '2026-1', 'Đỗ Thị Nga',      150, (1 << 0) | (1 << 13) | (1 << 18)), -- T2 ca1, T5 ca2, T6 ca3
    (11, 'ENG1001', '2026-1', 'Trịnh Văn Phúc',    2, (1 << 20) | (1 << 21)),          -- T7 ca1, T7 ca2
    (12, 'ENG1001', '2026-1', 'Lý Thị Quỳnh',     40, (1 << 22) | (1 << 24))           -- T7 ca3, CN ca1
ON CONFLICT (id) DO NOTHING;
SELECT setval(pg_get_serial_sequence('section', 'id'), (SELECT max(id) FROM section));

DROP EXTENSION pgcrypto;

SELECT (SELECT count(*) FROM account WHERE role = 'STUDENT') AS students,
       (SELECT count(*) FROM account WHERE role = 'ADMIN') AS admins,
       (SELECT count(*) FROM course) AS courses,
       (SELECT count(*) FROM section) AS sections,
       (SELECT status FROM registration_period WHERE term = '2026-1') AS period_status;
