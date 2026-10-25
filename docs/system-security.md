# Phân tích bảo mật hệ thống

> Tài liệu phân tích và định hướng bảo mật cho pha 1.

## Phạm vi và giả định

- Backend Spring Boot (Java 17, chạy được trên 21) và PostgreSQL, chạy bằng Docker.
- Chạy qua Tailscale trên bốn máy, không Internet công cộng.
- Giao tiếp HTTP trong mạng Tailscale. Tailscale mã hóa đường truyền nên pha 1 không dùng HTTPS riêng.
- Không có giao diện riêng; thử API qua Swagger UI và công cụ thử tải.

## Các thành phần cần bảo vệ

| Danh mục | Lý do |
| --- | --- |
| Tài khoản và mật khẩu (`account`) | Lộ thì mạo danh sinh viên hoặc quản trị |
| Khóa ký JWT | Lộ thì giả mạo token của mọi tài khoản |
| Tính đúng của `enrollment` và `enrolled_count` | Sai sĩ số, đăng ký trùng làm hỏng nghiệp vụ |
| Cấu hình đợt và lớp (`registration_period`, `section`) | Chỉ quản trị được đổi |
| Chuỗi kết nối và mật khẩu DB | Lộ thì đọc và sửa toàn bộ dữ liệu |

## Mô hình mối đe dọa

| Mối đe dọa | Cách xảy ra | Biện pháp | 
| --- | --- | --- |
| Dò mật khẩu | Gọi lặp `POST /auth/login` | Giới hạn tốc độ trong bộ nhớ ứng dụng; thông báo lỗi giống nhau cho sai tài khoản và sai mật khẩu; BCrypt |
| Giả mạo token | Đoán hoặc sửa JWT | Ký HS256 bằng khóa ngẫu nhiên từ biến môi trường; kiểm chữ ký và `exp` ở mọi request |
| Truy cập dữ liệu người khác - IDOR | Truyền `studentId` hoặc id lượt đăng ký của người khác | `studentId` lấy từ JWT, không nhận từ client; hủy lượt của người khác trả 404 (D13) |
| Leo thang quyền | Sinh viên gọi `/admin/**` | Kiểm `role` trong JWT bằng filter; sai vai trò trả 403 |
| SQL injection | Chèn SQL vào tham số `q`, `courseCode` | Truy vấn tham số hóa, không nối chuỗi |
| Đăng ký trùng, vượt sĩ số | Gửi đồng thời, gửi lặp | Khóa `account`, `UPDATE ... WHERE enrolled_count < capacity`, `UNIQUE`, `CHECK`, `Idempotency-Key` | Theo spec |
| Lộ trạng thái hệ thống | Dò `/health` | Chỉ IP trong danh sách cho phép, nguồn khác 403 `FORBIDDEN` (D19) |
| Lộ bí mật qua repo | Commit mật khẩu, khóa JWT, `.env` | `.env.example` chỉ chứa giá trị giả; quét secret trong CI |
| Lộ thông tin qua lỗi | Stack trace trong phản hồi | Lỗi theo dạng `{code, message, details}`; không trả chi tiết nội bộ |
| Truy cập DB trực tiếp | Kết nối thẳng cổng PostgreSQL | Cổng DB chỉ mở cho máy chạy API qua IP Tailscale |

## Xác thực và phân quyền

- Đăng nhập `POST /api/v1/auth/login` bằng `account.code` và mật khẩu.
- JWT 60 phút, chứa `account.id` và `role`. Không refresh token, không logout, máy chủ không lưu phiên.
- Xác thực nằm trong một filter của framework, không lặp trong từng endpoint.
- Khung giờ đăng nhập (D10): sinh viên chỉ đăng nhập trong `[login_opens_at, closes_at)` của đợt `OPEN`; quản trị đăng nhập mọi lúc, trừ trong `[opens_at, closes_at)` của đợt `OPEN`.
- Lỗi xác thực (401) và phân quyền (403) cần handler riêng để trả đúng dạng lỗi chung, vì lỗi từ filter không đi qua `@ControllerAdvice`.

### Truy cập từng nhóm endpoint

| Endpoint | Truy cập |
| --- | --- |
| `POST /api/v1/auth/login` | Công khai |
| `GET /api/v1/health` | Không dùng JWT; chỉ IP trong danh sách cho phép |
| `GET /api/v1/me`, `GET /api/v1/enrollments`, `POST /api/v1/enrollments`, `DELETE /api/v1/enrollments/{id}` | Cần JWT |
| `PUT /api/v1/admin/**` | Cần JWT với vai trò `ADMIN` |
| `GET /api/v1/registration`, `GET /api/v1/sections` | Công khai, chỉ trả về thông tin đợt đăng ký, lớp chung, không trả dữ liệu sinh viên. Giới hạn truy cập trong Tailscale |
| Swagger UI, `/v3/api-docs` | Chỉ các API cần bảo vệ mới yêu cầu JWT, còn lại để công khai|

## Bảo vệ dữ liệu và cấu hình

- Mật khẩu lưu dạng băm BCrypt.
- Khóa JWT, mật khẩu DB, chuỗi kết nối nằm trong biến môi trường. Repo chỉ có `.env.example`.
- Container API chạy bằng người dùng không phải root.
- Tài khoản DB của ứng dụng chỉ có quyền cần thiết; dữ liệu danh mục và tài khoản được nạp bằng tập lệnh riêng, không qua API.
- Dọn `idempotency_record` hết hạn bằng job định kỳ, không qua API (D14).

## Chống lạm dụng

- Giới hạn tốc độ trong bộ nhớ ứng dụng (spec không dùng Redis), ưu tiên `/auth/login`.
- Giới hạn kích thước body và số bản ghi mỗi trang.
- Kiểm tra đầu vào bằng Bean Validation: độ dài `Idempotency-Key` (1 đến 128 ký tự), `sectionIds` không trùng, kiểu và khoảng giá trị.
- Giới hạn tốc độ làm tăng độ phức tạp khi thử tải. Đo riêng bước đăng nhập và tách cấu hình thử tải khỏi cấu hình bình thường.

## Mạng và triển khai

- Cổng API và cổng DB: API `8080`, PostgreSQL `5432`, bind vào IP Tailscale của máy, không dùng `0.0.0.0`.
- Danh sách IP cho `/health` đặt qua biến môi trường, không viết cứng.
- Image dựng từ `Dockerfile` nhiều giai đoạn; image nền được cập nhật định kỳ.

## Kiểm thử bảo mật

Các ca cần có bằng chứng chạy lại được:

- Thiếu token trả 401; token hết hạn hoặc sai chữ ký trả 401.
- Sinh viên gọi `/admin/**` trả 403.
- Sinh viên A không đọc và không hủy được lượt đăng ký của sinh viên B (404).
- Đăng nhập ngoài khung giờ trả 403 `LOGIN_NOT_OPEN` hoặc `ADMIN_LOGIN_LOCKED`.
- `/health` từ IP ngoài danh sách trả 403 `FORBIDDEN`.
- Vượt giới hạn tốc độ ở `/auth/login` bị từ chối.
- Tham số có ký tự SQL không làm lỗi truy vấn hay thay đổi kết quả.
- Quét secret và quét lỗ hổng thư viện chạy trong CI.

## Giới hạn hiện tại

- Token bị lộ dùng được đến hết 60 phút; không có cơ chế thu hồi.
- Nếu khóa JWT bị lộ, kẻ tấn công giả mạo được token của mọi tài khoản.
- Nếu một máy trong mạng Tailscale bị chiếm, lớp phòng thủ mạng mất tác dụng.
- Không có HTTPS riêng; đường truyền dựa vào mã hóa của Tailscale.
- Giới hạn tốc độ trong bộ nhớ chỉ có tác dụng trong một tiến trình; chạy nhiều bản API thì giới hạn không chia sẻ.
- Không chống DDoS thể tích lớn.
- Chưa có nhật ký kiểm toán thay đổi của quản trị (ngoài phạm vi pha 1).
