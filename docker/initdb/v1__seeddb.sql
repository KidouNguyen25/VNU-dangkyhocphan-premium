-- Load lai sample db: docker compose up -d
-- Xoa db: docker compose down -v

-- 123456 hash
INSERT INTO account (code, full_name, password_hash, role) VALUES
('ADMIN01', 'Tralalero Tralala', '$2a$10$5i3r7fAfz7bX9xBxUGKReukM8cZBnWmaP46f4T9XcAH26MRISn3KO', 'ADMIN'),
('SV001', 'Nguyen Duc Toan', '$2a$10$5i3r7fAfz7bX9xBxUGKReukM8cZBnWmaP46f4T9XcAH26MRISn3KO', 'STUDENT'),
('SV002', 'tung tung tung sahur', '$2a$10$5i3r7fAfz7bX9xBxUGKReukM8cZBnWmaP46f4T9XcAH26MRISn3KO', 'STUDENT'),
('SV003', 'Nguyen Thanh Trung', '$2a$10$5i3r7fAfz7bX9xBxUGKReukM8cZBnWmaP46f4T9XcAH26MRISn3KO', 'STUDENT');

INSERT INTO course (code, name, credits, prerequisite) VALUES
('INT2204', 'Lập trình hướng đối tượng', 3, NULL),
('INT3406', 'Xử lý ngôn ngữ tự nhieen', 3, 'INT2210'),
('INT2210', 'Cấu trúc dữ liệu và giải thuật', 4, NULL);

INSERT INTO registration_period (term, login_opens_at, opens_at, closes_at, status, credit_limit) VALUES
('2026-1', NOW(), NOW() + INTERVAL '1 hour', NOW() + INTERVAL '7 days', 'OPEN', 24);

INSERT INTO section (course_code, term, lecturer, capacity, enrolled_count, session) VALUES
('INT3406 1', '2026-1', 'chatgpt và claude', 2, 2, 32),
('INT2204 1', '2026-1', 'el gato', 2, 0, 32);

INSERT INTO enrollment (student_id, section_id, status, created_at) VALUES
('SV001', 'INT3406 1', "Enrolled", NOW()),
('SV002', 'INT3406 1', 'Enrolled', NOW());