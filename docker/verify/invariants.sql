-- Kiểm tra bất biến dữ liệu. Chỉ đọc, không sửa dữ liệu.
-- Mỗi truy vấn trả 0 dòng khi dữ liệu đúng; có dòng nghĩa là bất biến bị vi phạm.
-- Chạy: bash load/scripts/run_invariants.sh

\echo '[a] enrolled_count khác số dòng ENROLLED của lớp'
SELECT s.id AS section_id, s.enrolled_count, count(e.id) AS enrolled_rows
FROM section s
LEFT JOIN enrollment e ON e.section_id = s.id AND e.status = 'ENROLLED'
GROUP BY s.id, s.enrolled_count
HAVING s.enrolled_count <> count(e.id);

\echo '[b] enrolled_count ngoài [0, capacity]'
SELECT id AS section_id, enrolled_count, capacity
FROM section
WHERE enrolled_count < 0 OR enrolled_count > capacity;

\echo '[c] một sinh viên có hai lớp cùng course_code trong một kỳ'
SELECT e.student_id, s.term, s.course_code, count(*) AS sections
FROM enrollment e
JOIN section s ON s.id = e.section_id
WHERE e.status = 'ENROLLED'
GROUP BY e.student_id, s.term, s.course_code
HAVING count(*) > 1;

\echo '[d] một sinh viên có hai lớp trùng bit session trong một kỳ'
SELECT e1.student_id, s1.term, s1.id AS section_a, s2.id AS section_b, s1.session & s2.session AS overlap
FROM enrollment e1
JOIN section s1 ON s1.id = e1.section_id
JOIN enrollment e2 ON e2.student_id = e1.student_id AND e2.section_id > e1.section_id
JOIN section s2 ON s2.id = e2.section_id AND s2.term = s1.term
WHERE e1.status = 'ENROLLED' AND e2.status = 'ENROLLED'
  AND (s1.session & s2.session) <> 0;

\echo '[e] tổng tín chỉ vượt credit_limit'
SELECT e.student_id, s.term, sum(c.credits) AS total_credits, p.credit_limit
FROM enrollment e
JOIN section s ON s.id = e.section_id
JOIN course c ON c.code = s.course_code
JOIN registration_period p ON p.term = s.term
WHERE e.status = 'ENROLLED'
GROUP BY e.student_id, s.term, p.credit_limit
HAVING sum(c.credits) > p.credit_limit;

\echo '[f] bản ghi idempotency PROCESSING quá hạn'
SELECT id, student_id, idempotency_key, created_at, expires_at
FROM idempotency_record
WHERE state = 'PROCESSING' AND expires_at <= clock_timestamp();

\echo '[g] nhiều hơn một đợt OPEN'
SELECT status, count(*) AS periods
FROM registration_period
WHERE status = 'OPEN'
GROUP BY status
HAVING count(*) > 1;
