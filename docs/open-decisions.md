# Nhật ký quyết định

## Danh sách quyết định

| ID | Câu hỏi | Kết luận hoặc hướng hiện có | Trạng thái |
| --- | --- | --- | --- |
| D1 | Đăng ký nhiều lớp trong một request xử lý thế nào? | All-or-nothing. `sectionIds` không trùng, cùng kỳ mở, xét trong một giao dịch. | Đã chốt 23/10/2026 |
| D2 | Một lớp lỗi giữa chừng thì xử lý chỗ đã giữ thế nào? | Rollback toàn bộ, không còn chỗ giữ mồ côi. Phản hồi lỗi nghiệp vụ lưu vào `idempotency_record` rồi commit; lỗi 5xx rollback và cho gửi lại. | Đã chốt 23/10/2026 |
| D3 | Khi nào dùng hàng đợi và trả 202? | MVP đồng bộ, không trả 202. Hàng đợi chỉ xét sau thử tải; khi đó cần trạng thái chờ, endpoint tra cứu trạng thái, cơ chế gửi lại và kiểm thử mới. | Đã chốt cho MVP |
| D4 | Nếu sau này dùng hàng đợi, lưu ở PostgreSQL hay Redis? | Chưa chọn, chưa tạo bảng hàng đợi. | Hoãn, ngoài MVP |
| D5 | Quy tắc trùng ca, tín chỉ, giờ, hủy, quyền quản trị? | Đã chốt, xem D10 đến D19 bên dưới. | Đã chốt, chờ nhóm xác nhận |
| D6 | Kịch bản 10.000 người dùng và 50.000 yêu cầu đo thế nào? | 50.000 là tổng số yêu cầu theo kịch bản. Tăng tải dần; đo riêng đăng nhập; báo cáo p95, p99, thông lượng, lỗi, CPU, RAM, Tailscale, độ đúng dữ liệu sau tải. Chưa có ngưỡng đạt và thời lượng phát tải. | Mở một phần |
| D7 | Đề bài, rubric, yêu cầu nộp và hạn nộp chính thức? | Chưa có trong bản ghi. Nếu giảng viên chưa công bố hạn thì ghi rõ như vậy. | Mở |
| D8 | Cả nhóm chấp thuận phạm vi pha 1 và các quyết định? | Cần xác nhận của cả bốn thành viên sau khi đối chiếu nguồn chính thức. | Mở |
| D9 | Bảng idempotency và cách gửi lại | Thêm `idempotency_record`, TTL 24 giờ, khóa `UNIQUE (student_id, idempotency_key)`. Cùng khóa khác hash trả 409 `IDEMPOTENCY_KEY_REUSED`. Không thêm bảng nào khác. | Đã chốt 23/10/2026 |

## Quyết định đã chốt ngày 24/10/2026

| ID | Quyết định | Lý do |
| --- | --- | --- |
| D10 | Quản trị đăng nhập mọi lúc, trừ trong `[opens_at, closes_at)` của đợt `OPEN`. Chỉ chặn đăng nhập mới; token đã cấp vẫn dùng đến hết 60 phút. Đóng đợt sớm chỉ dành cho sự cố. | Tránh quản trị thay đổi dữ liệu và tạo tải khi sinh viên đang đăng ký, nhưng vẫn xử lý được sự cố. |
| D11 | `section.session` là bitmask 28 bit (7 ngày x 4 ca); trùng ca khi `(a.session & b.session) <> 0`. DB có `CHECK (session > 0 AND session < 268435456)`. | Lớp học có 2 hoặc 3 buổi mỗi tuần; bitmask biểu diễn được mà không thêm bảng. |
| D12 | MVP không ghi dòng `FAILED` vào `enrollment`. Enum `FAILED` và `CHECK` giữ lại; xử lý chi tiết ở pha 2. | Với all-or-nothing, lỗi nghiệp vụ nằm trong `idempotency_record` nên không cần dòng `FAILED`. |
| D13 | Hủy không có lượt nào khớp (đã hủy, không tồn tại, hoặc của người khác) trả 404 `ENROLLMENT_NOT_FOUND`. Hủy lần hai trả 404. Lượt của người khác cũng trả 404. | Không lộ id nào có thật. Thay yêu cầu cũ là hủy lặp trả 204. |
| D14 | Dọn `idempotency_record` hết hạn bằng job định kỳ, không qua API. | API không cần quyền xóa trên bảng này. |
| D15 | Enum chốt: `account_role` (`STUDENT`, `ADMIN`), `registration_status` (`OPEN`, `CLOSED`), `enrollment_status` (`ENROLLED`, `FAILED`). Không có `SCHEDULED`. | Theo DB hiện hành. Đợt chưa mở là đợt `CLOSED` có `opens_at` ở tương lai. |
| D16 | Thời khóa biểu thật có 4 ca mỗi ngày (xác nhận). Chuyển sang tiết (12 tiết mỗi ngày) để pha 2. | Bitmask 28 bit đủ cho 4 ca; 84 bit của 12 tiết cần `bit(84)` hoặc bảng `section_schedule`. |
| D17 | Chấp nhận mã lỗi thêm: `ADMIN_LOGIN_LOCKED`, `ENROLLMENT_NOT_FOUND`, `CAPACITY_BELOW_ENROLLED`, `OPEN_PERIOD_EXISTS`, `SECTION_HAS_ENROLLMENTS`. Tài liệu `Danh sách endpoints` đã được đồng bộ theo SRS. | Bổ sung cho các luật mới; tránh dùng một mã chung cho lỗi khác nghĩa. |
| D18 | Lớp đã có lượt đăng ký (`enrolled_count > 0`) không được đổi `course_code`, `term`, `session`; trả 422 `SECTION_HAS_ENROLLMENTS`. `capacity` và `lecturer` vẫn đổi được. Muốn đổi thì tạo lớp mới. Kiểm ở service, không dùng trigger. | Đổi ba trường này làm hỏng ngầm trùng học phần, tín chỉ, kỳ và trùng ca của sinh viên đã đăng ký; DB không chặn được. |
| D19 | `GET /health` riêng tư: chỉ máy nhóm phát triển trong danh sách địa chỉ cho phép (Tailscale) gọi được, không dùng JWT; nguồn khác 403 `FORBIDDEN`. | Không để máy khách thử tải hay sinh viên dò trạng thái hệ thống; vẫn gọi được khi quản trị bị khóa đăng nhập. |

## Việc còn mở

- Ngưỡng đạt và thời lượng phát tải (D6).
- Chuyển lịch học từ ca sang tiết (12 tiết mỗi ngày) ở pha 2.


## Đối chiếu hợp đồng API — T1 (28/10/2026)

| # | Hạng mục | Trạng thái spec | Trạng thái code | Điểm lệch |
|---|----------|-----------------|-----------------|-----------|
| 1 | 10 endpoint đủ | Có trong `phase-1-scope.md` | Đủ 10 endpoint trong `ApiController` | Khớp |
| 2 | `POST /enrollments` trả 201 | 201 `{items:[{enrollmentId,sectionId,createdAt}]}` | Đúng | Khớp |
| 3 | `DELETE /enrollments` trả 204 | 204 no body | Đúng | Khớp |
| 4 | Idempotency-Key header bắt buộc | 1–128 ký tự, phạm vi (student_id, key), TTL 24h | `@Size(min=1,max=128)` | Khớp |
| 5 | `X-Idempotent-Replay` header | Ghi nhận trong `k5-backend.md` | Có trong controller response | Khớp |
| 6 | ETag + 304 cho `/sections` và `/registration` | Có | Có | Khớp |
| 7 | Tiêu đề OpenAPI không ghi "K5" | Chưa quy định chính thức | Cũ ghi "K5"; đã sửa thành "VNU Đăng ký học phần" | Đã sửa 28/10/2026 |
| 8 | `API-SPECIFICATION.md` tĩnh | Chưa có file; OpenAPI sinh runtime qua springdoc | `openapi.yaml` tạo thủ công từ runtime spec | Đã chốt: runtime `/v3/api-docs`; `docs/openapi.yaml` và `docs/openapi.json` là snapshot |
| 9 | Mã lỗi 422 `SECTION_TERM_MISMATCH` | Có trong `k5-backend.md` | Có trong `@ApiResponse` | Khớp |
| 10 | `PUT /admin/sections` 200 update / 201 create | Có | Có | Khớp |
| 11 | `GET /health` IP whitelist, không JWT | D19 đã chốt | `HEALTH_ALLOWED_IPS` | Khớp |

> **D20 (đã chốt):** nguồn chính thức là `/v3/api-docs`. Snapshot tĩnh nằm ở `docs/openapi.yaml` và `docs/openapi.json`; không giữ bản `openapi.yaml` thứ hai ở thư mục gốc.
