# Backend

Nhánh `feat/k5-backend` sử dụng Java Spring Boot. Backend có `JwtTokenGateway` để phát và xác minh JWT HS256. Chưa triển khai lên máy nhóm hoặc đo tải qua Tailscale.

## Phạm vi và cấu trúc

```text
src/main/java/vn/edu/vnu/registration/
  api/       controller, kiểm tra dữ liệu, xử lý lỗi, bảo vệ request, giới hạn tốc độ
  domain/    mô hình, cổng tích hợp, luật nghiệp vụ Java thuần
  data/      ánh xạ PostgreSQL, transaction/savepoint
  config/    Spring bean, SecurityFilterChain, OpenAPI
src/test/    PostgreSQL tạm, dữ liệu và ca kiểm thử
```

`RegistrationService` chỉ phụ thuộc Java chuẩn và các cổng trong `Ports`. Repository dùng Spring JDBC với SQL tham số hóa; mỗi transaction dùng một connection do Spring quản lý. Backend không dùng JPA auto-DDL, Redis, queue, worker, bảng phụ hoặc mã phản hồi 202. `course.prerequisite` chỉ được trả về, không dùng để kiểm tra môn tiên quyết.

## JWT và cấu hình khóa

`JwtTokenGateway` triển khai `vn.edu.vnu.registration.domain.Ports.TokenGateway`:

```java
public interface TokenGateway {
    Token issue(Identity identity);
    Identity verify(String bearerToken);
}
// Identity(long accountId, Role role)
// Token(String accessToken, String tokenType, long expiresIn)
```

- `issue`: phát JWT HS256 có thời hạn 60 phút, `tokenType = "Bearer"`, `expiresIn = 3600`; claim `iss` là `vnu-course-registration`, `sub` là `account.id`, kèm `role`, `iat` và `exp`.
- `verify`: chỉ chấp nhận HS256; kiểm tra chữ ký, issuer, thời hạn, `account.id` dương và role hợp lệ. Token sai hoặc hết hạn trả 401 `UNAUTHORIZED`. Không tin payload trước khi kiểm tra chữ ký.
- Filter nhận `Authorization: Bearer ...`, gọi gateway và đặt `Identity` vào Spring SecurityContext. Controller lấy danh tính qua `@AuthenticationPrincipal`; không nhận `studentId` hoặc `role` từ body hay query.
- Đặt `JWT_SECRET` trong biến môi trường. Khóa phải ngẫu nhiên và có ít nhất 32 byte UTF-8. `.env.example` chỉ chứa giá trị mẫu; gateway từ chối giá trị mẫu bắt đầu bằng `change-me`.
- Khi `JWT_SECRET` trống, gateway dự phòng trả 503 `AUTH_NOT_CONFIGURED` khi phát hoặc xác minh token.
- Backend không lưu session, refresh token, trạng thái logout hoặc khóa ký. Khóa không được ghi vào repo.
- Luồng đăng nhập kiểm tra bcrypt và khung giờ trước khi gọi `issue`. Nếu nhóm dùng Argon2, cần thay `PasswordVerifier` trong cấu hình service và thống nhất định dạng `account.password_hash` trước khi tích hợp.
- ADMIN không thể đăng nhập mới trong khoảng `[opensAt, closesAt)` của đợt OPEN. Filter không kiểm tra lại khung giờ với token đã cấp; ADMIN vẫn xử lý được sự cố đến khi token hết hạn.

`JwtTokenGatewayTest` kiểm tra phát token, vai trò, thời hạn, sai khóa, payload bị sửa, token sai định dạng và khóa yếu. Kiểm thử tích hợp còn dùng gateway giả, chỉ có trong test classpath. Chưa chạy lại bộ kiểm thử sau khi thêm gateway.

## API

Các endpoint dùng base path `/api/v1`, JSON camelCase và thời gian ISO 8601 UTC. Lỗi có dạng `{code, message, details}`. Phản hồi 401 có `WWW-Authenticate: Bearer`; phản hồi 429 có `Retry-After` tính bằng giây. Annotation sinh mã lỗi và schema tại `/v3/api-docs`, Swagger UI hiển thị tài liệu này.

| Phương thức | Đường dẫn | Quyền | Phản hồi thành công |
| --- | --- | --- | --- |
| `POST` | `/auth/login` | Công khai | 200 `{accessToken, tokenType, expiresIn}` |
| `GET` | `/me` | Đã xác thực | 200 `{id, code, fullName, role, creditLimit}` |
| `GET` | `/registration` | Công khai trong mạng Tailscale | 200 `{registration, serverTime}`; `registration=null` khi không có OPEN |
| `GET` | `/sections` | Công khai trong mạng Tailscale | 200 `{items, nextCursor}` |
| `GET` | `/enrollments` | `STUDENT` | 200 `{items, creditsByTerm}` |
| `POST` | `/enrollments` | `STUDENT` | 201 `{items: [{enrollmentId, sectionId, createdAt}]}` |
| `DELETE` | `/enrollments/{enrollmentId}` | `STUDENT` | 204, không có body |
| `PUT` | `/admin/sections/{sectionId}` | `ADMIN` | 201 khi tạo; 200 khi cập nhật |
| `PUT` | `/admin/registration` | `ADMIN` | 200, bản ghi upsert theo `term` |
| `GET` | `/health` | IP được phép, không cần JWT | 200 `{status, serverTime}` sau `SELECT 1` |

`GET /sections` hỗ trợ các bộ lọc `term`, `courseCode`, `q`, `hasSeats`, `cursor` và `limit`. `q` tìm chuỗi nguyên văn trong tên môn, mã môn hoặc giảng viên. `cursor` là id cuối trang trước; `limit` từ 1 đến 100, mặc định 20. `hasSeats=false` lọc lớp đã đầy.

`GET /enrollments` nhận bộ lọc `term` tùy chọn. Trường `id` trong mỗi lượt đăng ký là id lớp; dùng `enrollmentId` để hủy. Tổng tín chỉ được nhóm theo kỳ.

`GET /registration` và `GET /sections` trả ETag cùng `Cache-Control: private, max-age=0, must-revalidate`, `Vary: Authorization`; hỗ trợ `If-None-Match` và 304. `X-Server-Time` luôn chứa giờ hiện tại. ETag của đợt dựa trên nội dung đợt, không dựa trên `serverTime` thay đổi liên tục. Không dùng dữ liệu cache để quyết định chỗ đăng ký.

Ví dụ đăng ký. Sau khi đặt `JWT_SECRET`, dùng Swagger Authorize với JWT do backend phát:

```http
POST /api/v1/enrollments
Authorization: Bearer <JWT>
Content-Type: application/json
Idempotency-Key: sv01-dangky-001

{"sectionIds":[1,2]}
```

Danh sách nhận từ 1 đến 100 id nguyên dương, không trùng. Các mảng `[1,2]` và `[2,1]` được chuẩn hóa thành cùng hash. Cùng `(studentId,key)` và hash sẽ phát lại mã trạng thái cùng JSON body đã commit trong 24 giờ. Khác hash trả 409 `IDEMPOTENCY_KEY_REUSED`.

Header `X-Idempotent-Replay` cho biết phản hồi được phát lại. Nếu đã hủy rồi gửi lại khóa cũ, hệ thống vẫn trả phản hồi lịch sử. Muốn đăng ký lại, phải dùng khóa mới.

Mã lỗi nghiệp vụ:

- 403: `REGISTRATION_NOT_OPEN`.
- 404: `SECTION_NOT_FOUND`, `ENROLLMENT_NOT_FOUND`.
- 409: `SECTION_FULL`, `SECTION_ALREADY_ENROLLED`, `COURSE_ALREADY_ENROLLED`, `IDEMPOTENCY_KEY_REUSED`, `OPEN_PERIOD_EXISTS`.
- 422: `SESSION_CONFLICT`, `CREDIT_LIMIT_EXCEEDED`, `SECTION_TERM_MISMATCH`, `CAPACITY_BELOW_ENROLLED`, `SECTION_HAS_ENROLLMENTS`, `COURSE_NOT_FOUND`, `REGISTRATION_NOT_FOUND`.

## Giao dịch và đối chiếu với cơ sở dữ liệu

![Luồng hoạt động trên kiến trúc](assets/luong-kien-truc.gif)

### Đăng ký

1. Bắt đầu transaction ở mức cô lập `READ COMMITTED`; khóa `account` bằng `FOR UPDATE`.
2. Tạo hoặc nhận bản ghi idempotency bằng `INSERT ... ON CONFLICT ...`. Nếu bản ghi còn hạn, so sánh hash và phát lại phản hồi. Nếu đã hết hạn, tái sử dụng bản ghi bằng `UPDATE`, không cần quyền `DELETE`.
3. Tạo savepoint; đọc và khóa chia sẻ đợt OPEN; khóa các `section` theo id tăng dần. Kiểm tra lại thời gian sau khi chờ khóa.
4. Dưới khóa account, kiểm tra kỳ, lớp trùng, học phần trùng, lịch bitmask và tín chỉ. Với mỗi lớp, chạy `UPDATE` có điều kiện `enrolled_count < capacity`, rồi `INSERT` lượt đăng ký.
5. Nếu có lỗi nghiệp vụ, rollback đến savepoint và lưu phản hồi lỗi vào idempotency. Nếu có lỗi kỹ thuật, rollback toàn bộ để client có thể gửi lại cùng khóa.
6. Ghi trạng thái `COMPLETED` và body; đọc lại JSONB làm body chuẩn cho lần đầu và lần phát lại. Commit trước khi trả phản hồi HTTP.

**Thứ tự khóa đã điều chỉnh:** khóa `account` trước khi thêm bản ghi idempotency. Với FK `idempotency_record.student_id → account.id`, lệnh `INSERT` giữ khóa `KEY SHARE` trên account. Hai request của cùng sinh viên nhưng khác key có thể deadlock nếu cùng chèn rồi nâng khóa lên `FOR UPDATE`. Kiểm thử đã tái hiện lỗi này. Khóa account trước khi chèn loại bỏ vòng chờ; thứ tự vẫn là account rồi section. Thay đổi không sửa bảng; nhóm cần cập nhật sơ đồ trình tự khi tích hợp.

![Luồng đăng ký](assets/luong-dang-ky.gif)

### Hủy đăng ký

Trong một transaction, backend khóa account, đọc đợt, xác minh lượt đăng ký thuộc sinh viên hiện tại, khóa section, xóa lượt và giảm bộ đếm. Không tìm thấy lượt, lượt đã hủy hoặc lượt thuộc người khác đều trả 404; không hoàn thêm chỗ. Không thể hủy lượt thuộc kỳ khác với đợt đang mở.

![Luồng hủy](assets/luong-huy.gif)

### Cập nhật lớp và đợt

`PUT` lớp dùng advisory lock theo id để tuần tự hóa cả trường hợp lớp chưa tồn tại, sau đó khóa dòng section. Nếu lớp đã có sinh viên, không đổi `course_code`, `term` hoặc `session`; không giảm capacity dưới `enrolled_count`. `PUT` đợt dựa vào partial UNIQUE để ngăn hai đợt cùng OPEN.

### Quyền của API

Ngoài quyền `CONNECT` và `USAGE` trên schema, API cần các quyền sau:

```sql
GRANT SELECT ON account, course TO dkhp_api;
-- PostgreSQL yêu cầu UPDATE để SELECT FOR UPDATE; chỉ cấp trên cột id.
GRANT UPDATE (id) ON account TO dkhp_api;
GRANT SELECT, INSERT, UPDATE ON registration_period, section, idempotency_record TO dkhp_api;
GRANT SELECT, INSERT, UPDATE, DELETE ON enrollment TO dkhp_api;
GRANT USAGE, SELECT ON SEQUENCE section_id_seq, enrollment_id_seq, idempotency_record_id_seq TO dkhp_api;
```

`v1__initdb.sql` tạo các sequence và tên index/FK mà lớp ánh xạ sử dụng: `one_open_period`, `section_course_code_fkey`, `section_term_fkey`.

`docker/initdb/v1__initdb.sql` khởi tạo schema trên volume PostgreSQL mới; `v2__roles.sh` tạo quyền cho API. Init script không nâng cấp volume hiện hữu. `src/test/resources/schema.sql` là fixture cho cùng sáu bảng, ràng buộc và index, không tự chạy trên DB triển khai.

### Dọn bản ghi idempotency

Owner chạy lệnh sau theo lịch để xóa bản ghi hết hạn. API không có endpoint dọn dữ liệu:

```sql
DELETE FROM idempotency_record WHERE expires_at <= clock_timestamp();
```

## Chạy và đóng gói

### Môi trường

- JDK 17 trở lên; Maven Wrapper trong repo hoặc Maven 3.9 trở lên.
- PostgreSQL có schema đã được rà soát.
- Xem `.env.example`. Khai báo biến trong shell hoặc IDE; Spring Boot không tự nạp `.env`.
- Backend mặc định bind vào loopback. Khi chạy qua Tailscale, đặt `SERVER_ADDRESS` bằng IP Tailscale của máy backend và `HEALTH_ALLOWED_IPS` bằng IP máy phát triển. Không tin `X-Forwarded-For`.
- CORS chỉ áp dụng cho `/api/v1/**`. Đặt `CORS_ALLOWED_ORIGINS` bằng origin của frontend (phân cách bằng dấu phẩy); mặc định là `http://localhost:3000` và `http://localhost:5173`. Origin khác nhận 403 ở preflight.
- `HEALTH_ALLOWED_IPS` chỉ nhận IP cụ thể, không nhận hostname hoặc CIDR. Cổng DB chỉ mở cho backend theo cấu hình mạng nhóm.

### Lệnh chạy

Windows:

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

Linux/macOS:

```sh
sh mvnw test
sh mvnw package
```

Chạy JAR và đóng gói Docker:

```sh
java -jar target/course-registration-0.1.0-SNAPSHOT.jar
docker build -t vnu-dkhp-api .
```

Ví dụ chạy container qua loopback. Nếu DB nằm ngoài container, `DB_URL` phải dùng địa chỉ máy DB, không dùng `localhost`:

```sh
docker run --rm --env-file .env -e SERVER_ADDRESS=0.0.0.0 -p 127.0.0.1:8080:8080 vnu-dkhp-api
```

Khi chuyển sang Tailscale, thay địa chỉ bind của `-p` bằng IP Tailscale của máy backend. Docker NAT có thể làm IP nguồn API nhận được khác IP máy khách. Cần kiểm tra địa chỉ socket thực tế để cấu hình allowlist; không tin header do client tự gửi. Dockerfile chạy bằng user không phải root và không chứa secret. Chưa kiểm chứng Docker trên máy hiện tại vì máy không có Docker.

### Giới hạn tốc độ và log

Rate limit trong bộ nhớ: 600 request/phút theo IP, 120 request/phút theo tài khoản, 10 lần đăng nhập/phút. Có thể chỉnh qua env. Cửa sổ 60 giây; map tối đa 20.000 khóa. MVP dùng một instance; nhiều instance cần cơ chế giới hạn dùng chung.

Log ghi request ID, method, HTTP status, thời gian xử lý API và DB. Log không ghi body, mật khẩu, token hoặc lỗi SQL có chứa giá trị. Tắt DEBUG của Spring Web/JDBC trong môi trường có dữ liệu thật.

## Kiểm chứng và giới hạn

`mvn test` tự chạy PostgreSQL embedded trên loopback với cổng ngẫu nhiên, không cần DB chung. Fixture được tạo trong DB tạm. Tests dùng user `api_test` với quyền tối thiểu như trên, không có quyền `DELETE` bản ghi idempotency. Mỗi ca chạy SQL để kiểm tra bộ đếm, học phần trùng, ca trùng, vượt tín chỉ và bản ghi `PROCESSING` mồ côi.

Các nhóm kiểm thử:

- API: 10 endpoint, Swagger, 401/403, quyền sở hữu, khung giờ, validation, cursor, bộ lọc và ETag.
- Giao dịch: rollback cả lô khi lớp cuối đã đầy; bảo vệ lớp đã có sinh viên; kiểm tra tranh chỗ cuối với 20 sinh viên.
- Idempotency: phát lại thành công/lỗi, đổi thứ tự payload, key khác payload, TTL, tạo lại service trên cùng DB, lỗi kỹ thuật để thử lại, hai request dùng cùng key đồng thời.
- Đồng thời: hai request cùng sinh viên cho cùng lớp, môn, ca và tín chỉ; hủy đồng thời.
- Token giả chỉ nằm trong test classpath, không được đóng gói vào JAR.

Sau khi chạy test, xem `target/surefire-reports` và `target/evidence/openapi.json`. Các kiểm thử này xác nhận tính đúng, không chứng minh hệ thống đạt tải 1.000 người dùng/5.000 request hoặc kết quả đo trên bốn máy. Trước nghiệm thu nhóm, cần cấu hình khóa JWT, dựng DB mới từ init script v1, xác nhận hợp đồng API và chạy lại kiểm thử tích hợp.

### Kết quả kiểm chứng trước khi bổ sung JWT (25/10/2026)

- Môi trường: Windows, JDK 21.0.8, Maven Wrapper 3.9.9, biên dịch với `release 17`, PostgreSQL embedded 14.22.
- Maven Wrapper `package`: **21 test, 0 lỗi, 0 bỏ qua** (15 kiểm thử tích hợp, 4 luật nghiệp vụ, 2 filter). Ca cố tình gây lỗi DB có log ERROR dự kiến; rollback và gửi lại đã được xác minh.
- Đã tạo `target/course-registration-0.1.0-SNAPSHOT.jar`. JAR không chứa lớp kiểm thử, fixture hoặc PostgreSQL embedded.
- Chạy JAR trên loopback: Swagger UI và `/v3/api-docs` trả 200; `/api/v1/me` không có bearer trả 401; bearer khi chưa có gateway trả 503. Dịch vụ kiểm tra tạm đã dừng.
- Tại mốc này chưa kiểm chứng Docker, JWT, DB chung, Tailscale hoặc tải trên bốn máy. Kiểm thử replay tạo lại service trên cùng DB để xác nhận không phụ thuộc bộ nhớ; chưa restart tiến trình.
- Sau mốc này đã thêm `JwtTokenGateway` và kiểm thử riêng; chưa chạy lại Maven Wrapper.
