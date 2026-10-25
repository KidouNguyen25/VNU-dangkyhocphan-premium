-- Load lai sample db: docker compose up -d
-- Xoa db: docker compose down -v

INSERT INTO account (code, full_name, password_hash, role) VALUES
('ADMIN01', 'Tralalero Tralala', '123456', 'ADMIN'),
('SV001', 'Nguyen Duc Toan', '123456', 'STUDENT'),
('SV002', 'tung tung tung sahur', '123456', 'STUDENT');

INSERT INTO course (code, name, credits, prerequisite) VALUES
('INT2204', 'Lập trình hướng đối tượng', 3, NULL),
('INT3406', 'Xử lý ngôn ngữ tự nhieen', 3, 'INT2210'),
('INT2210', 'Cấu trúc dữ liệu và giải thuật', 4, NULL);

INSERT INTO period (term, login_opens_at, opens_at, closes_at, status, credit_limit) VALUES
('2026-1', NOW(), NOW() + INTERVAL '1 hour', NOW() + INTERVAL '7 days', 'OPEN', 24);

INSERT INTO section (course_code, term, lecturer, capacity, enrolled_count, session) VALUES
('INT3406', '2026-1', 'chatgpt và claude', 2, 0, 32);