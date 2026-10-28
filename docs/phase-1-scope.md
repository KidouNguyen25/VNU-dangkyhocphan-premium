# Phạm vi pha 1

> Đây là tài liệu đặc tả để triển khai.

## Mục tiêu

- Pha 1 là một backend đăng ký học phần chạy được và thử được trực tiếp trên Swagger UI. Không xây frontend riêng.
- Số request lớn chưa đủ để coi là đạt. Khi nhiều sinh viên tranh cùng một chỗ, hệ thống vẫn phải không đăng ký trùng, không vượt sĩ số và không bỏ qua ràng buộc nghiệp vụ.
- Dữ liệu dùng các bảng PostgreSQL: `account`, `course`, `registration_period`, `section`, `enrollment`, `idempotency_record`. Không thêm bảng hay cột nào khác ngoài sơ đồ cơ sở dữ liệu.

## Nghiệp vụ

| Nhóm | Nghiệp vụ | Mô tả | Pha |
| --- | --- | --- | --- |
| Xác thực | Đăng nhập | `account.code` và mật khẩu, trả JWT 60 phút chứa `account.id` và `role`; không refresh token, không logout, máy chủ không lưu phiên | 1 |
| | Khung giờ sinh viên | Chỉ đăng nhập trong `[login_opens_at, closes_at)` của đợt `OPEN`; ngoài khung trả 403 `LOGIN_NOT_OPEN` | 1 |
| | Khung giờ quản trị | Đăng nhập mọi lúc, trừ trong `[opens_at, closes_at)` của đợt `OPEN` (403 `ADMIN_LOGIN_LOCKED`). Chỉ chặn đăng nhập mới; token đã cấp vẫn dùng đến hết 60 phút để xử lý sự cố | 1 |
| | Phân quyền | Vai trò `STUDENT`, `ADMIN` lấy từ JWT; không nhận `studentId` từ máy khách | 1 |
| Quản trị | Đợt đăng ký | Upsert theo `term`: mốc giờ, `status` (`OPEN`, `CLOSED`), `credit_limit`. Tối đa một đợt `OPEN` | 1 |
| | Lớp học phần | Upsert `course_code`, `term`, `lecturer`, `capacity`, `session`. Không hạ `capacity` dưới `enrolled_count` | 1 |
| Tra cứu | Đợt, lớp, lượt đăng ký | Lọc lớp theo `term`, `courseCode`, `q`, `hasSeats`; phân trang cursor. Chỗ trống chỉ để hiển thị | 1 |
| Điều kiện | Thời gian | Đăng ký và hủy trong `[opens_at, closes_at)` của đợt `OPEN`, theo giờ máy chủ | 1 |
| | Sĩ số | Lớp đầy trả 409 `SECTION_FULL`; không có danh sách chờ | 1 |
| | Tín chỉ | Tổng `credits` các lượt `ENROLLED` trong kỳ cộng lớp mới không vượt `credit_limit`; vượt trả 422 `CREDIT_LIMIT_EXCEEDED` | 1 |
| | Trùng lớp | `UNIQUE (student_id, section_id)`; trả 409 `SECTION_ALREADY_ENROLLED` | 1 |
| | Trùng học phần | Tối đa một lớp cho mỗi `course_code` trong một kỳ; kiểm bằng JOIN dưới khóa `account`; trả 409 `COURSE_ALREADY_ENROLLED` | 1 |
| | Trùng ca học | `session` là bitmask 28 bit. Hai lớp trùng khi `(a.session & b.session) <> 0`; trả 422 `SESSION_CONFLICT` | 1 |
| | Tiên quyết | Chỉ lưu và trả về `course.prerequisite`; không kiểm, không có lỗi `PREREQUISITE_NOT_MET` | Ngoài MVP |
| Giao dịch | Đăng ký | Nhiều `sectionIds` theo all-or-nothing trong một giao dịch, đồng bộ, trả 201 | 1 |
| | Hủy | Xóa lượt `ENROLLED`, giảm `enrolled_count` đúng một lần, trả 204. Không có lượt nào khớp trả 404 `ENROLLMENT_NOT_FOUND` | 1 |
| | Đồng thời | Khóa `account` `FOR UPDATE`, rồi khóa `section` theo `id` tăng dần; `UPDATE ... WHERE enrolled_count < capacity`; `CHECK` là chốt chặn cuối | 1 |
| | Gửi lặp | `Idempotency-Key` bắt buộc (1 đến 128 ký tự), phạm vi `(student_id, key)`, TTL 24 giờ. Cùng khóa và cùng hash phát lại phản hồi; khác hash trả 409 `IDEMPOTENCY_KEY_REUSED` | 1 |
| Tài liệu | OpenAPI, Swagger UI | Mô tả và thử API; không có giao diện riêng | 1 |

## Lịch học (`section.session`)

- `session` là bitmask 28 bit. Bit `(i - 1)` bằng 1 nếu lớp có buổi ở ca `i`, với `i` từ 1 đến 28.
- Ca `i = (thứ - 1) x 4 + ca trong ngày`. Thứ 1 là Thứ Hai, thứ 7 là Chủ nhật. Ca 1 và 2 là sáng, ca 3 và 4 là chiều.
- Lớp học 2 hoặc 3 buổi mỗi tuần bật 2 hoặc 3 bit. Ví dụ Thứ Hai ca 1 và Thứ Tư ca 3 là ca 1 và ca 11, giá trị `2^0 + 2^10 = 1025`.
- DB giữ `CHECK (session > 0 AND session < 268435456)`.
- Thời khóa biểu thật có 4 ca mỗi ngày (2 sáng, 2 chiều), đã được xác nhận. Việc chuyển sang tiết (một ngày có 12 tiết) để pha 2 xử lý.
- Bitmask không biểu diễn giờ bắt đầu, giờ kết thúc hay phòng học. Khi cần các thông tin này thì dùng bảng `section_schedule` ở pha sau. Chuyển sang tiết (12 tiết mỗi ngày, 84 bit mỗi tuần) vượt kiểu `BIGINT`, nên pha 2 dùng `bit(84)` hoặc bảng `section_schedule`.

## Giao dịch đăng ký và hủy

![Đăng ký: xác thực, ghi idempotency, khóa dữ liệu, kiểm ràng buộc, commit](assets/luong-dang-ky.gif)

Đăng ký (`POST /enrollments`), tất cả trong một giao dịch:

- Ghi `idempotency_record` trạng thái `PROCESSING` với `UNIQUE (student_id, idempotency_key)`. Nếu khóa đã có thì chờ giao dịch kia, rồi so hash và phát lại phản hồi đã commit.
- Khóa `account` của sinh viên `FOR UPDATE` để tuần tự hóa các đăng ký của cùng một người.
- Kiểm tra đợt và thời gian; khóa các `section` mục tiêu theo `id` tăng dần.
- Kiểm tra trùng lớp, trùng học phần, trùng ca (`bit_or` của `session`) và tín chỉ.
- Với mỗi lớp: `UPDATE section SET enrolled_count = enrolled_count + 1 WHERE id = :id AND enrolled_count < capacity`, rồi `INSERT enrollment` ở trạng thái `ENROLLED`.
- Ghi `response_status`, `response_body`, đặt `state = COMPLETED`, `COMMIT`. Chỉ sau commit mới trả phản hồi.

Lỗi và hủy:

- Lỗi nghiệp vụ dự đoán được (403 ngoài giờ, 409, 422) hoàn tác phần `enrollment` và `enrolled_count`, nhưng vẫn ghi phản hồi lỗi vào `idempotency_record` rồi commit, để gửi lại cùng khóa nhận cùng lỗi.
- Lỗi nội bộ (5xx) rollback toàn bộ và cho phép gửi lại.
- Hủy: lấy `studentId` từ JWT; khóa `account`; tìm `enrollment` `ENROLLED` khớp `id` và `student_id`. Không có dòng nào thì rollback và trả 404. Có dòng thì khóa `section`, xóa dòng, giảm `enrolled_count` đúng một lần, commit, trả 204.
- Thứ tự khóa cố định ở cả đăng ký lẫn hủy: `account` trước, `section` theo `id` tăng dần sau.

## API triển khai

| Phương thức | Đường dẫn | Mục đích dự kiến |
| --- | --- | --- |
| `POST` | `/api/v1/auth/login` | Đăng nhập |
| `GET` | `/api/v1/me` | Đọc thông tin tài khoản hiện tại |
| `GET` | `/api/v1/registration` | Đọc đợt đăng ký |
| `GET` | `/api/v1/sections` | Tra cứu lớp học phần |
| `GET` | `/api/v1/enrollments` | Đọc lượt đăng ký |
| `POST` | `/api/v1/enrollments` | Đăng ký lớp; bắt buộc header `Idempotency-Key` |
| `DELETE` | `/api/v1/enrollments/{enrollmentId}` | Hủy lượt đăng ký |
| `PUT` | `/api/v1/admin/sections/{sectionId}` | Tạo hoặc cập nhật lớp học phần; lớp đã có lượt đăng ký không được đổi `course_code`, `term`, `session` (422 `SECTION_HAS_ENROLLMENTS`) |
| `PUT` | `/api/v1/admin/registration` | Tạo hoặc cập nhật đợt đăng ký |
| `GET` | `/api/v1/health` | Kiểm tra tình trạng dịch vụ; riêng tư, chỉ nhóm phát triển (danh sách địa chỉ cho phép, không dùng JWT) |

## Ma trận ràng buộc nghiệp vụ

| Ràng buộc | Kết quả cần bảo đảm | Nơi giữ |
| --- | --- | --- |
| Sĩ số | `0 <= enrolled_count <= capacity`; khớp số dòng `ENROLLED` của lớp | `CHECK` + `UPDATE` có điều kiện |
| Trùng lớp | `UNIQUE (student_id, section_id)` | DB |
| Trùng học phần | Tối đa một lớp cho mỗi `course_code` trong một kỳ | Giao dịch + khóa `account` |
| Tín chỉ | Tổng `credits` các lượt `ENROLLED` trong kỳ không vượt `credit_limit` | Giao dịch + khóa `account` |
| Trùng ca | Không có hai lớp chung bit trong `session` trong một kỳ | Giao dịch + khóa `account` |
| Ca học hợp lệ | `0 < session < 268435456` | `CHECK` |
| Thời gian | Đăng nhập sinh viên trong `[login_opens_at, closes_at)`; đăng ký và hủy trong `[opens_at, closes_at)` | Backend |
| Một đợt mở | Tối đa một dòng `OPEN` | Partial UNIQUE index |
| Gửi lặp | Cùng khóa cùng hash: phát lại; khác hash: 409 | `idempotency_record` |
| Hủy | Giảm `enrolled_count` đúng một lần; hủy lặp trả 404 | Giao dịch |
| Nhiều lớp | All-or-nothing | Giao dịch |

Số chỗ đọc từ ETag hay bộ nhớ đệm chỉ để hiển thị, không dùng để quyết định đăng ký.

## Yêu cầu phi chức năng

- Tải pha 1: 1000 người dùng đồng thời và 5000 yêu cầu. 5000 là tổng số yêu cầu theo kịch bản, không phải 5000 yêu cầu mỗi giây. Pha 2 tăng tải lên số lượng gấp 10 lần pha 1.
- Đo tải: báo cáo cấu hình, người dùng ảo, tổng yêu cầu, thông lượng, p95, p99, tỷ lệ lỗi, CPU, RAM, độ trễ Tailscale, tỷ lệ phát lại idempotency và trạng thái dữ liệu sau tải. Ngưỡng đạt chưa quyết định, sau khi triển khai thì sẽ đo và quyết định sau.
- Mô hình thử tải: bốn máy, hai máy phía máy chủ (backend, PostgreSQL) và hai máy khách mỗi máy giả lập 500 tài khoản ở pha 1. Đo riêng bước đăng nhập vì băm mật khẩu tốn CPU.
- Nhất quán: PostgreSQL là nguồn có thẩm quyền; giao dịch ngắn, đồng bộ.
- Không dùng Redis, hàng đợi, worker và không trả 202 trong MVP. Giới hạn tốc độ đặt trong bộ nhớ ứng dụng.
- Giao tiếp: REST tại `/api/v1`, JSON UTF-8, camelCase, thời gian ISO 8601 UTC, lỗi `{code, message, details}`, OpenAPI 3.x, Swagger UI.
- Mạng: Tailscale, không mở ra Internet công cộng. Cổng DB chỉ mở cho máy chạy API.
- Bảo mật: không đưa mật khẩu, khóa JWT, chuỗi kết nối thật hay dữ liệu thật vào repo; dùng `.env.example`.

## Ngoài phạm vi

- Giao diện người dùng riêng; triển khai ra Internet công cộng.
- Redis, hàng đợi, worker, phản hồi 202.
- Kiểm tra tiên quyết đã đạt (cần bảng `student_course_result`).
- Giờ bắt đầu, giờ kết thúc, phòng học (cần bảng `section_schedule`).
- Dòng `FAILED` trong `enrollment`: MVP không ghi, xử lý chi tiết ở pha 2.
- Lịch sử hủy đăng ký; nhật ký thay đổi của quản trị.
- Bảng nào ngoài các bảng đã nêu.
- Khung chương trình đào tạo, vai trò giảng viên, danh sách chờ, học cải thiện, kiểm tra nợ học phí, tính học phí và báo cáo thống kê.

## Kế hoạch triển khai

| Mốc | Điều kiện hoàn thành |
| --- | --- |
| A. Chốt đề bài và hợp đồng | Đề bài và rubric đã đối chiếu; cơ sở dữ liệu đã được rà soát; hợp đồng lỗi và `Đặc tả API` được cả nhóm duyệt; ma trận kiểm thử và khung OpenAPI có sẵn |
| B. Khóa kiến trúc và dữ liệu | Dựng PostgreSQL, dữ liệu mẫu; API và Swagger UI chạy; `POST` ghi `idempotency_record`, `enrollment`, `enrolled_count` trong một giao dịch; bản đầu của C4 và trình tự |
| C. Tích hợp nghiệp vụ | Không oversell, không trùng, không vượt tín chỉ, không trùng ca; gửi lại cùng khóa trả cùng phản hồi, khác payload trả 409; hủy không trả chỗ hai lần; TC01 đến TC12 và TC14 có bằng chứng chạy lại được |
| D. Đo, hoàn thiện, nộp | Triển khai qua Tailscale trên bốn máy; thử tải tăng dần kèm SQL hậu kiểm; báo cáo kiến trúc, trình diễn Swagger UI; nộp đúng yêu cầu khi hạn nộp được xác nhận |
