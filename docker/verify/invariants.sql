-- Script kiểm tra toàn vẹn các bất biến của hệ thống đăng ký học phần (Phase 1)
-- Mỗi truy vấn DƯỚI ĐÂY PHẢI TRẢ VỀ 0 DÒNG nếu dữ liệu đúng và nhất quán.

-- enrolled_count khác số dòng ENROLLED của lớp
SELECT s.id AS section_id,
       s.enrolled_count,
       count(e.id) AS actual_enrolled_count
FROM section s
LEFT JOIN enrollment e ON e.section_id = s.id AND e.status = 'ENROLLED'
GROUP BY s.id, s.enrolled_count
HAVING s.enrolled_count <> count(e.id);

-- enrolled_count ngoài [0, capacity]
SELECT id AS section_id,
       capacity,
       enrolled_count
FROM section
WHERE enrolled_count < 0 OR enrolled_count > capacity;

-- một sinh viên có hai lớp cùng course_code trong một kỳ
SELECT e.student_id,
       s.term,
       s.course_code,
       count(*) AS enrolled_classes_count
FROM enrollment e
JOIN section s ON e.section_id = s.id
WHERE e.status = 'ENROLLED'
GROUP BY e.student_id, s.term, s.course_code
HAVING count(*) > 1;

-- một sinh viên có hai lớp trùng bit session trong một kỳ
SELECT e1.student_id,
       s1.term,
       s1.id AS section_id_1,
       s1.session AS session_1,
       s2.id AS section_id_2,
       s2.session AS session_2,
       (s1.session & s2.session) AS conflict_bits
FROM enrollment e1
JOIN section s1 ON e1.section_id = s1.id
JOIN enrollment e2 ON e1.student_id = e2.student_id AND e1.id < e2.id
JOIN section s2 ON e2.section_id = s2.id
WHERE e1.status = 'ENROLLED'
  AND e2.status = 'ENROLLED'
  AND s1.term = s2.term
  AND (s1.session & s2.session) <> 0;

-- tổng tín chỉ vượt credit_limit
SELECT e.student_id,
       s.term,
       sum(c.credits) AS total_credits,
       rp.credit_limit
FROM enrollment e
JOIN section s ON e.section_id = s.id
JOIN course c ON s.course_code = c.code
JOIN registration_period rp ON s.term = rp.term
WHERE e.status = 'ENROLLED'
GROUP BY e.student_id, s.term, rp.credit_limit
HAVING sum(c.credits) > rp.credit_limit;

-- bản ghi PROCESSING quá hạn
SELECT id,
       student_id,
       idempotency_key,
       state,
       created_at,
       expires_at
FROM idempotency_record
WHERE state = 'PROCESSING'
  AND expires_at <= clock_timestamp();

-- nhiều hơn một đợt OPEN
SELECT count(*) AS open_periods_count
FROM registration_period
WHERE status = 'OPEN'
HAVING count(*) > 1;
