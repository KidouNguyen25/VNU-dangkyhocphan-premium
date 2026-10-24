# Bàn giao backend K5

Nhánh `feat/k5-backend`. Stack theo xác nhận của người yêu cầu: Java Spring Boot. JWT do đồng đội phụ trách; phần này cung cấp điểm tích hợp, không tự ký/giải mã JWT. Chưa triển khai lên máy nhóm hoặc đo tải Tailscale.

## Phạm vi và cấu trúc

```text
src/main/java/vn/edu/vnu/registration/
  api/       controller, validation, lỗi, bảo vệ request, rate limit
  domain/    mô hình, cổng tích hợp, luật nghiệp vụ thuần Java
  data/      PostgreSQL data mapper và transaction/savepoint
  config/    Spring beans, SecurityFilterChain, OpenAPI
src/test/    PostgreSQL thật tạm thời, fixture và test
```

`RegistrationService` chỉ phụ thuộc Java chuẩn và các cổng trong `Ports`. Repository dùng Spring JDBC với SQL tham số hóa; Spring quản lý một connection cho mỗi transaction. Không dùng JPA auto-DDL, Redis, queue, worker, bảng phụ hoặc 202. `course.prerequisite` chỉ được trả về, không kiểm kết quả môn tiên quyết.

## Tích hợp JWT

Đồng đội cung cấp **một Spring bean** triển khai `vn.edu.vnu.registration.domain.Ports.TokenGateway`:

```java
public interface TokenGateway {
    Token issue(Identity identity);
    Identity verify(String bearerToken);
}
// Identity(long accountId, Role role)
// Token(String accessToken, String tokenType, long expiresIn)
```

- `issue`: JWT 60 phút, `tokenType = "Bearer"`, `expiresIn = 3600`, chứa `account.id` và vai trò `STUDENT`/`ADMIN`. Thống nhất tên claim với đồng đội; API không phụ thuộc tên claim cụ thể.
- `verify`: kiểm chữ ký, thuật toán cho phép, thời hạn và các claim cần thiết; trả `Identity` đã xác minh. Token không hợp lệ/hết hạn phải ném `BusinessException(401, "UNAUTHORIZED", ...)` hoặc exception xác thực. Không giải mã rồi tin payload khi chưa kiểm chữ ký.
- Filter nhận `Authorization: Bearer ...`, gọi gateway, đặt `Identity` vào Spring SecurityContext. Controller lấy danh tính qua `@AuthenticationPrincipal`; không nhận `studentId`/`role` từ body/query.
- Không lưu session, refresh token, logout hoặc khóa ký trong module này. Khi chưa có bean `TokenGateway`, gateway dự phòng từ chối bằng 503 `AUTH_NOT_CONFIGURED`; khi có bean của đồng đội, service và filter tự dùng bean đó.
- Luồng đăng nhập đã kiểm bcrypt và khung giờ trước khi gọi `issue`. Nếu nhóm dùng Argon2, thay `PasswordVerifier` trong cấu hình service và thống nhất định dạng `account.password_hash` trước tích hợp.
- Không đăng nhập mới được khi ADMIN nằm trong `[opensAt, closesAt)` của đợt OPEN. Filter không áp dụng lại luật đăng nhập với token đã cấp, nên ADMIN vẫn có thể xử lý sự cố tới lúc token hết hạn.

Hợp đồng này cần được ghép và kiểm thử lại với JWT thật; test hiện tại dùng gateway giả **chỉ trên test classpath**.

## API

Base path `/api/v1`, JSON camelCase, thời gian ISO 8601 UTC. Lỗi luôn có `{code, message, details}`; 401 có `WWW-Authenticate: Bearer`; 429 có `Retry-After` tính bằng giây. Mã lỗi và schema chi tiết được sinh từ annotation tại `/v3/api-docs` và hiển thị trên Swagger UI.

| Method | Đường dẫn | Quyền | Thành công |
| --- | --- | --- | --- |
| POST | `/auth/login` | Công khai | 200 `{accessToken, tokenType, expiresIn}` |
| GET | `/me` | Đã xác thực | 200 `{id, code, fullName, role, creditLimit}` |
| GET | `/registration` | Đã xác thực | 200 `{registration, serverTime}`; `registration=null` khi không có OPEN |
| GET | `/sections` | Đã xác thực | 200 `{items, nextCursor}` |
| GET | `/enrollments` | STUDENT | 200 `{items, creditsByTerm}` |
| POST | `/enrollments` | STUDENT | 201 `{items: [{enrollmentId, sectionId, createdAt}]}` |
| DELETE | `/enrollments/{enrollmentId}` | STUDENT | 204, không có body |
| PUT | `/admin/sections/{sectionId}` | ADMIN | 201 khi tạo; 200 khi cập nhật |
| PUT | `/admin/registration` | ADMIN | 200 bản ghi upsert theo `term` |
| GET | `/health` | IP được phép, không cần JWT | 200 `{status, serverTime}` sau `SELECT 1` |

`GET /sections` hỗ trợ `term`, `courseCode`, `q` (tên môn/mã môn/giảng viên, tìm literal), `hasSeats`, `cursor` (id cuối trang trước), `limit` (1–100, mặc định 20). `hasSeats=false` lọc lớp đầy. `GET /enrollments` lọc `term` tùy chọn. Trường `id` trong mỗi item lượt đăng ký là id lớp; id dùng hủy nằm trong `enrollmentId`. Tổng tín chỉ được nhóm theo kỳ.

`GET /registration` và `/sections` trả ETag, `Cache-Control: private, max-age=0, must-revalidate`, `Vary: Authorization`, hỗ trợ `If-None-Match`/304. `X-Server-Time` luôn chứa giờ hiện tại; ETag đợt dựa trên nội dung đợt, không dựa trên serverTime thay đổi liên tục. Không dùng dữ liệu cache để quyết định chỗ đăng ký.

Ví dụ đăng ký (Swagger Authorize bằng JWT thật sau khi tích hợp gateway):

```http
POST /api/v1/enrollments
Authorization: Bearer <JWT>
Content-Type: application/json
Idempotency-Key: sv01-dangky-001

{"sectionIds":[1,2]}
```

Danh sách 1–100 id nguyên dương, không trùng. Các mảng `[1,2]` và `[2,1]` được chuẩn hóa cùng hash. Cùng `(studentId,key)`/hash phát lại đúng status và JSON body đã commit trong 24 giờ; khác hash trả 409 `IDEMPOTENCY_KEY_REUSED`. Header `X-Idempotent-Replay` cho biết phản hồi là replay. Đã hủy rồi gửi lại khóa cũ vẫn nhận phản hồi lịch sử; đăng ký lại phải dùng khóa mới.

Các mã nghiệp vụ: `REGISTRATION_NOT_OPEN` (403), `SECTION_NOT_FOUND`/`ENROLLMENT_NOT_FOUND` (404), `SECTION_FULL`/`SECTION_ALREADY_ENROLLED`/`COURSE_ALREADY_ENROLLED`/`IDEMPOTENCY_KEY_REUSED`/`OPEN_PERIOD_EXISTS` (409), `SESSION_CONFLICT`/`CREDIT_LIMIT_EXCEEDED`/`SECTION_TERM_MISMATCH`/`CAPACITY_BELOW_ENROLLED`/`SECTION_HAS_ENROLLMENTS`/`COURSE_NOT_FOUND`/`REGISTRATION_NOT_FOUND` (422).

## Giao dịch và điểm cần đối chiếu với Quyền

1. `BEGIN READ COMMITTED`; khóa `account FOR UPDATE`.
2. Claim dòng idempotency bằng `INSERT ... ON CONFLICT ...`. Nếu còn hạn, so hash và replay. Nếu hết hạn, tái dùng dòng bằng UPDATE, không cần quyền DELETE.
3. Tạo savepoint; đọc và khóa chia sẻ đợt OPEN; khóa `section` theo id tăng dần. Kiểm lại thời gian sau khi chờ khóa.
4. Kiểm kỳ, cùng lớp, cùng học phần, bitmask lịch và tín chỉ dưới khóa account. Với mỗi lớp, UPDATE có điều kiện `enrolled_count < capacity`, rồi INSERT enrollment.
5. Lỗi nghiệp vụ: rollback tới savepoint, lưu phản hồi lỗi vào idempotency. Lỗi kỹ thuật: rollback toàn bộ, cho phép gửi lại cùng khóa.
6. Ghi status/body COMPLETED, đọc lại JSONB làm body chuẩn cho cả lần đầu và replay; commit trước khi phản hồi HTTP.

**Điều chỉnh so với thứ tự trong tài liệu:** khóa account trước INSERT idempotency. Với FK `idempotency_record.student_id → account.id`, INSERT giữ KEY SHARE trên account. Hai request cùng sinh viên, khác key, nếu cùng chèn rồi nâng lên FOR UPDATE sẽ deadlock. Test đã tái hiện; khóa account trước loại bỏ vòng chờ này và vẫn giữ thứ tự account → section. Đổi này không thêm/sửa bảng, cần đồng bộ vào sơ đồ trình tự khi nhóm tích hợp.

Hủy khóa account → đọc đợt → xác minh enrollment của chính mình → khóa section → DELETE → giảm bộ đếm trong một transaction. Không tìm thấy, đã hủy hoặc của người khác đều 404, không hoàn thêm chỗ. Không hủy được lượt thuộc kỳ khác với đợt đang mở.

PUT lớp dùng advisory lock riêng theo id để tuần tự hóa cả trường hợp lớp chưa tồn tại, rồi khóa dòng section. Khi đã có sinh viên không đổi `course_code`, `term`, `session`; không hạ capacity dưới enrolled_count. PUT đợt dựa vào partial UNIQUE để chặn hai OPEN.

API cần các quyền dưới đây, ngoài quyền CONNECT/USAGE schema:

```sql
GRANT SELECT ON account, course TO dkhp_api;
-- PostgreSQL yêu cầu UPDATE để SELECT FOR UPDATE; chỉ cấp trên cột id.
GRANT UPDATE (id) ON account TO dkhp_api;
GRANT SELECT, INSERT, UPDATE ON registration_period, section, idempotency_record TO dkhp_api;
GRANT SELECT, INSERT, UPDATE, DELETE ON enrollment TO dkhp_api;
GRANT USAGE ON SEQUENCE section_id_seq, enrollment_id_seq, idempotency_record_id_seq TO dkhp_api;
```

Đối chiếu tên sequence với migration thật. Tên index/FK được mapper nhận diện cho lỗi quản trị: `one_open_period`, `section_course_code_fkey`, `section_term_fkey`. Nếu migration Quyền dùng tên khác, cập nhật mapping trước tích hợp. `src/test/resources/schema.sql` chỉ là fixture khớp sáu bảng đã duyệt, không tự chạy trên DB thật; nhánh này không tạo migration sản xuất.

Dọn bản ghi hết hạn do owner chạy theo lịch, không có API dọn:

```sql
DELETE FROM idempotency_record WHERE expires_at <= clock_timestamp();
```

## Chạy và đóng gói

JDK 17+, Maven Wrapper trong repo hoặc Maven 3.9+, PostgreSQL với schema do Quyền bàn giao. Windows dùng `.\mvnw.cmd`, Linux/macOS dùng `sh mvnw` hoặc `./mvnw` nếu file có quyền execute. Xem `.env.example`; khai báo các biến trong shell/IDE, `.env` không tự nạp bởi Spring Boot. Backend bind loopback mặc định. Khi chạy qua Tailscale, đặt `SERVER_ADDRESS` bằng IP Tailscale của máy backend và `HEALTH_ALLOWED_IPS` bằng IP máy phát triển; không tin `X-Forwarded-For`. Danh sách health là IP literal, không nhận hostname/CIDR. Cổng DB chỉ mở cho backend theo cấu hình mạng nhóm.

```sh
mvn test
mvn package
java -jar target/course-registration-0.1.0-SNAPSHOT.jar
docker build -t vnu-dkhp-api .
```

Ví dụ chạy container qua loopback (DB ngoài container phải dùng địa chỉ máy DB, không dùng localhost trong DB_URL):

```sh
docker run --rm --env-file .env -e SERVER_ADDRESS=0.0.0.0 -p 127.0.0.1:8080:8080 vnu-dkhp-api
```

Khi chuyển qua Tailscale, thay địa chỉ bind của `-p` bằng IP Tailscale máy backend. Docker NAT có thể làm source IP mà API thấy khác IP máy khách; kiểm tra và cấu hình health allowlist theo địa chỉ socket thực tế, tránh tin header do khách tự gửi. Dockerfile chạy user không phải root, không chứa secret. Chưa chạy kiểm chứng Docker trên máy hiện tại vì không có Docker.

Rate limit trong bộ nhớ: IP 600/phút, tài khoản 120/phút, mã đăng nhập 10/phút, chỉnh qua env. Cửa sổ 60 giây, map tối đa 20.000 khóa. Dùng một instance ở MVP; nhiều instance cần cơ chế giới hạn dùng chung. Log có request ID, method, HTTP status, thời gian API và DB; không ghi body, mật khẩu, token hay lỗi SQL chứa giá trị. Tắt DEBUG của Spring Web/JDBC trong môi trường dùng dữ liệu thật.

## Kiểm chứng và giới hạn

`mvn test` tự chạy PostgreSQL embedded trên loopback/cổng ngẫu nhiên, không cần DB chung. Fixture được tạo trong instance DB tạm. Tests dùng user `api_test` có cùng quyền tối thiểu ở trên, không có quyền DELETE idempotency. Mỗi ca chạy SQL kiểm bộ đếm, trùng học phần, trùng ca, vượt tín chỉ và bản ghi PROCESSING mồ côi.

Các nhóm ca: 10 endpoint và Swagger; 401/403, quyền sở hữu; thời gian mở/đóng; validation; cursor/filter/ETag; rollback lô khi lớp cuối đầy; replay thành công/lỗi, đổi thứ tự payload, key khác payload, TTL; tạo lại service mà vẫn replay từ DB; lỗi kỹ thuật cho retry; bảo vệ lớp đã có sinh viên; tranh chỗ cuối với 20 sinh viên; hai request cùng sinh viên cho cùng lớp/môn, ca và tín chỉ; cùng key đồng thời; hủy đồng thời. Token giả không được đóng gói vào JAR.

Sau test, xem `target/surefire-reports` và `target/evidence/openapi.json`. Đây là kiểm thử tính đúng, không phải bằng chứng đạt tải 1.000 người dùng/5.000 request hoặc phép đo bốn máy. Cần ghép JWT thật, migration của Quyền, xác nhận hợp đồng API với Tùng và chạy lại kiểm thử tích hợp trước nghiệm thu nhóm.

### Kết quả xác minh ngày 25/10/2026

- Windows, JDK 21.0.8, Maven Wrapper 3.9.9, biên dịch với `release 17`, PostgreSQL embedded 14.22.
- Maven Wrapper `package`: **21 test, 0 lỗi, 0 bỏ qua** (15 integration, 4 luật nghiệp vụ, 2 filter). Ca cố tình gây lỗi DB có một log ERROR dự kiến và đã xác minh rollback/retry.
- Đã tạo `target/course-registration-0.1.0-SNAPSHOT.jar`; kiểm tra JAR không chứa các lớp test, fixture hoặc PostgreSQL embedded.
- Chạy JAR thật trên loopback: Swagger UI và `/v3/api-docs` trả 200; `/api/v1/me` không có bearer trả 401; bearer khi chưa tích hợp JWT trả 503. Dịch vụ kiểm tra tạm đã dừng.
- Chưa kiểm chứng Docker, JWT thật, DB chung, Tailscale hoặc tải bốn máy. Test replay tạo lại đối tượng service trên cùng DB để kiểm chứng không dựa vào bộ nhớ; chưa thực hiện kịch bản restart tiến trình với JWT thật.
