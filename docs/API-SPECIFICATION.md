# Đặc tả API

Tài liệu mô tả 10 endpoint của backend hệ thống đăng ký học phần. Tài liệu đặc tả chi tiết API nằm ở [openapi.yaml](openapi.yaml) và [openapi.json](openapi.json) (OpenAPI 3.1.0), sinh từ `/v3/api-docs` của ứng dụng bằng bộ kiểm thử `BackendIntegrationTest`.

## 1. Quy ước chung

| Mục | Giá trị |
| --- | --- |
| Base path | `/api/v1` |
| Định dạng | JSON UTF-8, tên trường camelCase |
| Thời gian | ISO 8601 UTC, ví dụ `2026-10-28T01:00:00Z` |
| Xác thực | `Authorization: Bearer <JWT>` (HS256, sống 60 phút) |
| Lỗi | `{ "code": "...", "message": "...", "details": ... }` |
| Tài liệu tương tác | `/swagger-ui/index.html`, `/v3/api-docs` |

Mạng chạy qua Tailscale, không mở ra Internet công cộng.

## 2. Tổng hợp endpoint

| # | Phương thức | Đường dẫn | Ai gọi được | Thành công |
| --- | --- | --- | --- | --- |
| 1 | `POST` | `/auth/login` | Công khai | 200 |
| 2 | `GET` | `/me` | Đã đăng nhập | 200 |
| 3 | `GET` | `/registration` | Công khai | 200, 304 |
| 4 | `GET` | `/sections` | Công khai | 200, 304 |
| 5 | `GET` | `/enrollments` | `STUDENT` | 200 |
| 6 | `POST` | `/enrollments` | `STUDENT` | 201 |
| 7 | `DELETE` | `/enrollments/{enrollmentId}` | `STUDENT` | 204 |
| 8 | `PUT` | `/admin/sections/{sectionId}` | `ADMIN` | 200, 201 |
| 9 | `PUT` | `/admin/registration` | `ADMIN` | 200 |
| 10 | `GET` | `/health` | IP trong danh sách cho phép, không cần JWT | 200 |

Sinh viên lấy mã sinh viên từ JWT, không gửi `studentId` trong body hay query.

## 3. Chi tiết từng endpoint

### 3.1 `POST /auth/login`

Đăng nhập, nhận JWT. Sinh viên chỉ đăng nhập được trong `[loginOpensAt, closesAt)` của đợt đang `OPEN`. Quản trị đăng nhập mọi lúc, trừ trong `[opensAt, closesAt)` của đợt `OPEN`.

Body:

| Trường | Kiểu | Bắt buộc | Ràng buộc |
| --- | --- | --- | --- |
| `code` | string | có | không rỗng, tối đa 20 ký tự |
| `password` | string | có | không rỗng, tối đa 72 byte UTF-8 |

Phản hồi 200:

```json
{ "accessToken": "<JWT>", "tokenType": "Bearer", "expiresIn": 3600 }
```

Lỗi riêng: 401 `INVALID_CREDENTIALS`; 403 `LOGIN_NOT_OPEN` hoặc `ADMIN_LOGIN_LOCKED`; 429 khi quá 10 lần đăng nhập mỗi phút.

### 3.2 `GET /me`

Trả thông tin tài khoản trong JWT.

```json
{ "id": 1, "code": "SV001", "fullName": "Nguyen Van A", "role": "STUDENT", "creditLimit": 24 }
```

`role` là `STUDENT` hoặc `ADMIN`. `creditLimit` là hạn mức tín chỉ của đợt đang mở, có thể `null`.

### 3.3 `GET /registration`

Trả đợt `OPEN` hiện tại và giờ máy chủ. Không có tham số truy vấn; tham số lạ bị từ chối 400.

```json
{
  "registration": {
    "term": "2026-1",
    "loginOpensAt": "2026-10-28T00:00:00Z",
    "opensAt": "2026-10-28T01:00:00Z",
    "closesAt": "2026-11-04T01:00:00Z",
    "status": "OPEN",
    "creditLimit": 24
  },
  "serverTime": "2026-10-28T00:30:00Z"
}
```

`registration` là `null` khi không có đợt nào đang `OPEN`. Phản hồi có `ETag`; gửi `If-None-Match` khớp thì nhận 304. Header `X-Server-Time` luôn có giờ hiện tại.

### 3.4 `GET /sections`

Tra cứu lớp học phần, phân trang theo cursor.

| Tham số | Kiểu | Ràng buộc | Ý nghĩa |
| --- | --- | --- | --- |
| `term` | string | 1–10 ký tự | Lọc theo kỳ |
| `courseCode` | string | 1–12 ký tự | Lọc theo mã học phần |
| `q` | string | 1–150 ký tự | Tìm chuỗi nguyên văn trong tên môn, mã môn, giảng viên |
| `hasSeats` | boolean | | `true` chỉ lớp còn chỗ, `false` chỉ lớp đầy |
| `cursor` | integer | dương | Id lớp cuối của trang trước |
| `limit` | integer | 1–100, mặc định 20 | Số lớp mỗi trang |

Phản hồi 200:

```json
{
  "items": [
    {
      "id": 1, "courseCode": "INT3001", "term": "2026-1", "lecturer": "Nguyen Van B",
      "capacity": 60, "enrolledCount": 12, "availableSeats": 48, "session": 1025,
      "courseName": "Kiến trúc phần mềm", "credits": 3, "prerequisite": null
    }
  ],
  "nextCursor": 1
}
```

`nextCursor` là `null` ở trang cuối. `availableSeats` chỉ để hiển thị; hệ thống không dùng nó để quyết định đăng ký. Hỗ trợ `ETag` và 304.

### 3.5 `GET /enrollments`

Liệt kê lượt `ENROLLED` của sinh viên trong JWT. Tham số tùy chọn `term` (1–10 ký tự).

```json
{
  "items": [
    {
      "id": 1, "courseCode": "INT3001", "term": "2026-1", "lecturer": "Nguyen Van B",
      "capacity": 60, "enrolledCount": 13, "availableSeats": 47, "session": 1025,
      "courseName": "Kiến trúc phần mềm", "credits": 3, "prerequisite": null,
      "enrollmentId": 101, "createdAt": "2026-10-28T01:05:00Z"
    }
  ],
  "creditsByTerm": { "2026-1": 3 }
}
```

Chú ý: `id` trong mỗi phần tử là id của lớp. Dùng `enrollmentId` để hủy.

### 3.6 `POST /enrollments`

Đăng ký 1 đến 100 lớp trong một giao dịch, theo nguyên tắc tất cả hoặc không.

Header bắt buộc: `Idempotency-Key` (1–128 ký tự).

Body:

```json
{ "sectionIds": [1, 2] }
```

`sectionIds` gồm 1–100 số nguyên dương, không trùng.

Phản hồi 201:

```json
{ "items": [ { "enrollmentId": 101, "sectionId": 1, "createdAt": "2026-10-28T01:05:00Z" } ] }
```

Header phản hồi `X-Idempotent-Replay: true` nếu đây là bản phát lại.

Lỗi riêng:

| Mã HTTP | `code` | Khi nào |
| --- | --- | --- |
| 403 | `REGISTRATION_NOT_OPEN` | Ngoài `[opensAt, closesAt)` hoặc không có đợt `OPEN` |
| 404 | `SECTION_NOT_FOUND` | Có id lớp không tồn tại |
| 409 | `SECTION_FULL` | Lớp đã đủ sĩ số |
| 409 | `SECTION_ALREADY_ENROLLED` | Đã đăng ký lớp này |
| 409 | `COURSE_ALREADY_ENROLLED` | Đã có một lớp khác của cùng học phần trong kỳ |
| 409 | `IDEMPOTENCY_KEY_REUSED` | Cùng key nhưng nội dung khác |
| 422 | `SECTION_TERM_MISMATCH` | Lớp không thuộc kỳ của đợt đang mở |
| 422 | `SESSION_CONFLICT` | Trùng ca học |
| 422 | `CREDIT_LIMIT_EXCEEDED` | Vượt hạn mức tín chỉ |

### 3.7 `DELETE /enrollments/{enrollmentId}`

Hủy lượt đăng ký của chính mình trong đợt đang mở. Phản hồi 204, không có body, hoàn lại đúng một chỗ.

Lỗi riêng: 404 `ENROLLMENT_NOT_FOUND` khi lượt không tồn tại, đã hủy, hoặc thuộc người khác. Hủy lần hai cũng trả 404.

### 3.8 `PUT /admin/sections/{sectionId}`

Tạo hoặc cập nhật lớp học phần. Trả 201 khi tạo mới, 200 khi cập nhật.

| Trường | Kiểu | Bắt buộc | Ràng buộc |
| --- | --- | --- | --- |
| `courseCode` | string | có | không rỗng, tối đa 12 ký tự |
| `term` | string | có | không rỗng, tối đa 10 ký tự |
| `lecturer` | string | có | không rỗng, tối đa 100 ký tự |
| `capacity` | integer | có | 1–32767 |
| `session` | integer | có | 1–268435455 (bitmask 28 bit) |

Lỗi riêng, đều 422: `CAPACITY_BELOW_ENROLLED` (hạ `capacity` dưới số đã đăng ký), `SECTION_HAS_ENROLLMENTS` (đổi `courseCode`, `term` hoặc `session` của lớp đã có người đăng ký), `COURSE_NOT_FOUND`, `REGISTRATION_NOT_FOUND`.

### 3.9 `PUT /admin/registration`

Tạo hoặc cập nhật đợt đăng ký theo `term`. Tối đa một đợt `OPEN`.

| Trường | Kiểu | Bắt buộc | Ràng buộc |
| --- | --- | --- | --- |
| `term` | string | có | không rỗng, tối đa 10 ký tự |
| `loginOpensAt` | date-time | có | |
| `opensAt` | date-time | có | |
| `closesAt` | date-time | có | |
| `status` | string | có | `OPEN` hoặc `CLOSED` |
| `creditLimit` | integer | có | 1–32767 |

Phản hồi 200 là bản ghi sau khi lưu. Lỗi riêng: 409 `OPEN_PERIOD_EXISTS` khi đã có đợt `OPEN` khác.

### 3.10 `GET /health`

Kiểm tra dịch vụ bằng `SELECT 1`. Không cần JWT; chỉ IP trong `HEALTH_ALLOWED_IPS` gọi được, nguồn khác nhận 403 `FORBIDDEN`.

```json
{ "status": "ok", "serverTime": "2026-10-28T00:30:00Z" }
```

## 4. Mã trạng thái và mã lỗi

Mọi endpoint có thể trả các lỗi chung sau:

| Mã HTTP | `code` | Ý nghĩa |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Body, tham số hoặc header sai định dạng |
| 401 | `UNAUTHORIZED` | Thiếu hoặc sai JWT; phản hồi có `WWW-Authenticate: Bearer` |
| 403 | `FORBIDDEN` | Không đủ quyền |
| 429 | `RATE_LIMITED` | Quá giới hạn; header `Retry-After` tính bằng giây |
| 500 | `INTERNAL_ERROR` | Lỗi nội bộ, giao dịch đã rollback |
| 503 | `AUTH_NOT_CONFIGURED` hoặc `SERVICE_UNAVAILABLE` | Chưa cấu hình `JWT_SECRET` hoặc dịch vụ tạm ngưng; có thể gửi lại |

Giới hạn tốc độ mặc định: 600 yêu cầu mỗi phút theo IP, 120 theo tài khoản, 10 lần đăng nhập mỗi phút. Chỉnh bằng biến môi trường.

## 5. Gửi lặp an toàn (`Idempotency-Key`)

- Bắt buộc với `POST /enrollments`. Phạm vi `(sinh viên, key)`, hiệu lực 24 giờ.
- Hash tính trên `sectionIds` đã sắp xếp, nên `[1,2]` và `[2,1]` là cùng một yêu cầu.
- Cùng key và cùng hash: trả lại đúng mã trạng thái và body đã lưu, kèm `X-Idempotent-Replay: true`.
- Cùng key khác hash: 409 `IDEMPOTENCY_KEY_REUSED`.
- Lỗi nghiệp vụ (403, 409, 422) cũng được lưu và phát lại. Lỗi 5xx rollback toàn bộ, cho phép gửi lại cùng key.
- Hủy rồi muốn đăng ký lại phải dùng key mới.

## 6. Mô hình dữ liệu

| Schema | Trường |
| --- | --- |
| `Token` | `accessToken`, `tokenType`, `expiresIn` |
| `Period` | `term`, `loginOpensAt`, `opensAt`, `closesAt`, `status`, `creditLimit` |
| `SectionResponse` | `id`, `courseCode`, `term`, `lecturer`, `capacity`, `enrolledCount`, `availableSeats`, `session`, `courseName`, `credits`, `prerequisite` |
| `RegisteredResponse` | các trường của `SectionResponse`, thêm `enrollmentId`, `createdAt` |
| `ErrorBody` | `code`, `message`, `details` |

`session` là bitmask 28 bit: bit `(i - 1)` bằng 1 nếu lớp có buổi ở ca `i` (1–28). Ca `i = (thứ - 1) × 4 + ca trong ngày`, thứ 1 là Thứ Hai. Ví dụ Thứ Hai ca 1 và Thứ Tư ca 3 là bit 0 và bit 10, giá trị `1025`. Hai lớp trùng ca khi `(a.session & b.session) <> 0`.

`prerequisite` chỉ được lưu và trả về, hệ thống không kiểm tra điều kiện tiên quyết.

## 7. Ví dụ gọi API

```bash
# Đăng nhập
curl -s -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"code":"SV001","password":"<mat-khau>"}'

# Đăng ký hai lớp
curl -s -X POST http://127.0.0.1:8080/api/v1/enrollments \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: sv001-dangky-001' \
  -H 'Content-Type: application/json' \
  -d '{"sectionIds":[1,2]}'

# Hủy lượt đăng ký
curl -s -X DELETE http://127.0.0.1:8080/api/v1/enrollments/101 \
  -H "Authorization: Bearer $TOKEN"
```
