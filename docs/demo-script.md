# Kịch bản demo trên Swagger UI

Kịch bản trình diễn các luồng chính trong khoảng 15 phút, bắt đầu từ cơ sở dữ liệu vừa seed. Địa chỉ Swagger UI: `http://localhost:8080/swagger-ui.html`.

## Chuẩn bị (không tính vào 15 phút)

Khởi động hệ thống, kiểm tra `postgres` ở trạng thái healthy và `api` đang chạy:

```bash
docker compose up -d
docker compose ps
```

Nạp dữ liệu mẫu:

```bash
docker compose exec -T postgres \
  psql -U dkhp_app -d dkhp \
  -v pw='<mat-khau-sinh-vien>' \
  -f - < docker/seed/seed_dev.sql
```

Seed thành công khi kết quả cuối có `students=1000, admins=1, courses=20, sections=36, period_status=OPEN`. Nếu cơ sở dữ liệu đã có dữ liệu cũ, chạy `docker compose down -v`, `docker compose up -d` rồi seed lại.

Mở sẵn Swagger UI trên trình duyệt.

## Luồng demo

### 1. Mở Swagger UI (30 giây)

Swagger UI hiển thị 10 endpoint: đăng nhập, `/me`, `/registration`, `/sections`, ba endpoint `/enrollments` (xem, đăng ký, hủy), hai endpoint `/admin` và `/health`. Bước này chưa cần JWT.

### 2. Đăng nhập (1 phút)

Mở `POST /api/v1/auth/login`, chọn Try it out, nhập body:

```json
{ "code": "SV00001", "password": "<mat-khau>" }
```

Kết quả 200:

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 3600 }
```

Sao chép giá trị `accessToken`.

### 3. Authorize (30 giây)

Chọn Authorize ở góc trên bên phải, dán token vào ô `bearerAuth` (không cần thêm tiền tố `Bearer `), chọn Authorize rồi Close. Các request sau sẽ tự gửi kèm token.

### 4. Xem tài khoản (30 giây)

Gọi `GET /api/v1/me`, kết quả 200:

```json
{ "id": ..., "code": "SV00001", "fullName": "Sinh viên 00001", "role": "STUDENT", "creditLimit": 20 }
```

`role` là `STUDENT` cho thấy token hợp lệ.

### 5. Xem đợt đăng ký (30 giây)

Gọi `GET /api/v1/registration`, kết quả 200, trong đó `registration` có `"status": "OPEN"` và `"term": "2026-1"`. Sinh viên chỉ đăng ký được khi đợt ở trạng thái `OPEN`.

### 6. Xem danh sách lớp (1 phút)

Gọi `GET /api/v1/sections` không có bộ lọc, kết quả là 20 lớp đầu tiên (mặc định `limit=20`) kèm `nextCursor` để lấy trang tiếp theo. Thêm `hasSeats=true` để chỉ lấy lớp còn chỗ.

Lưu ý lớp `id=11` (ENG1001) có `capacity` bằng 2; bước 9 sẽ dùng lớp này.

### 7. Đăng ký lớp (1 phút)

Gọi `POST /api/v1/enrollments` với header `Idempotency-Key: demo-sv00001-001` và body:

```json
{ "sectionIds": [3] }
```

Kết quả 201, không có header `X-Idempotent-Replay`:

```json
{ "items": [ { "enrollmentId": 1, "sectionId": 3, "createdAt": "2026-..." } ] }
```

Ghi lại `enrollmentId` để dùng ở bước 10.

### 8. Gửi lại cùng key (1 phút)

Giữ nguyên header và body của bước 7, chọn Execute lần nữa. Kết quả vẫn là 201 với body giống bước 7 và có thêm header `X-Idempotent-Replay: true`. Như vậy, khi người dùng bấm hai lần hoặc request bị gửi lại, hệ thống không tạo thêm lượt đăng ký.

### 9. Lớp đầy (2 phút)

Lớp 11 có 2 chỗ. Lần lượt đăng ký lớp 11 bằng ba tài khoản, mỗi lần dùng một key khác nhau:

1. SV00001, key `demo-sv00001-sec11`: 201.
2. SV00002 (đăng nhập và Authorize lại), key `demo-sv00002-001`: 201, lớp đã đủ sĩ số.
3. SV00003, key `demo-sv00003-001`: 409.

```json
{ "code": "SECTION_FULL", "message": "...", "details": ... }
```

Để rút ngắn thời gian, có thể sửa seed để lớp 11 có `capacity` bằng 1.

### 10. Hủy đăng ký (30 giây)

Đăng nhập lại bằng SV00001. Gọi `DELETE /api/v1/enrollments/{enrollmentId}` với id ở bước 7. Kết quả 204, body rỗng, lớp 3 được hoàn lại một chỗ.

### 11. Hủy lần hai (30 giây)

Gửi lại đúng request ở bước 10. Kết quả 404 `ENROLLMENT_NOT_FOUND`; số chỗ của lớp không thay đổi.

## Tóm tắt

| Bước | Request | Kết quả |
| --- | --- | --- |
| 2 | `POST /auth/login` | 200, lấy JWT |
| 4 | `GET /me` | 200, `role=STUDENT` |
| 5 | `GET /registration` | 200, `status=OPEN` |
| 6 | `GET /sections` | 200, lọc `hasSeats` |
| 7 | `POST /enrollments` | 201 |
| 8 | `POST /enrollments` cùng key | 201, `X-Idempotent-Replay: true` |
| 9 | `POST /enrollments` lớp 11 | 409 `SECTION_FULL` |
| 10 | `DELETE /enrollments/{id}` | 204 |
| 11 | `DELETE /enrollments/{id}` lần hai | 404 `ENROLLMENT_NOT_FOUND` |

## Xử lý sự cố

| Hiện tượng | Cách xử lý |
| --- | --- |
| Không mở được Swagger UI | Chạy `docker compose ps`, kiểm tra `api` |
| Đăng nhập trả 401 | Kiểm tra mật khẩu đã truyền qua `pw` khi seed |
| Đăng nhập trả 403 `LOGIN_NOT_OPEN` | Đợt chưa `OPEN` hoặc đang ngoài giờ đăng nhập |
| Endpoint trả 401 | Token hết hạn hoặc chưa Authorize; đăng nhập lại |
| Lớp 11 không còn đủ 2 chỗ | Chạy `docker compose down -v`, `docker compose up -d` rồi seed lại |
| 409 xuất hiện sớm hơn dự kiến | Đã có lượt đăng ký trước đó; kiểm tra cơ sở dữ liệu đã được làm sạch |
