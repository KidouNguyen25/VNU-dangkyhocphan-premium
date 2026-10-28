# Kịch bản Trình diễn Swagger UI

> **Mục tiêu:** Trình diễn toàn bộ luồng chính của hệ thống đăng ký học phần trong **< 15 phút** từ một DB sạch vừa được seed.
>
> **Swagger UI:** `http://localhost:8080/swagger-ui.html`

---

## Chuẩn bị (Trước khi demo — không tính vào 15 phút)

### Bước 0.1 — Khởi động hệ thống

```bash
docker compose up -d
docker compose ps
# Đảm bảo cả postgres (healthy) và api (running) đều UP
```

### Bước 0.2 — Seed dữ liệu từ DB sạch

```bash
docker compose exec -T postgres \
  psql -U dkhp_app -d dkhp \
  -v pw='<mat-khau-sinh-vien>' \
  -f - < docker/seed/seed_dev.sql
```

**Kết quả mong đợi:**

```
NOTICE:  students=1000, admins=1, courses=8, sections=12, period_status=OPEN
INSERT 0 1000
INSERT 0 1
...
```

> Nếu thấy `students=1000, courses=8, sections=12, period_status=OPEN` là seed thành công. Nếu DB không sạch, chạy `docker compose down -v` rồi `up -d` lại trước khi seed.

### Bước 0.3 — Mở tab trình duyệt

Mở sẵn: `http://localhost:8080/swagger-ui.html`

---

## Luồng demo chính (≈ 15 phút)

---

### Bước 1 — Mở Swagger UI *(30 giây)*

**Thao tác:**
- Truy cập `http://localhost:8080/swagger-ui.html` trong trình duyệt

**Kết quả mong đợi:**
- Giao diện Swagger hiển thị đầy đủ **10 endpoint** chia theo nhóm:
  - `auth` — đăng nhập
  - `me` — thông tin tài khoản
  - `registration` — đợt đăng ký
  - `sections` — danh sách lớp
  - `enrollments` — đăng ký / hủy

**Ghi chú:** Không cần JWT ở bước này.

---

### Bước 2 — Đăng nhập lấy JWT *(1 phút)*

**Thao tác:**
1. Mở rộng **`POST /api/v1/auth/login`**
2. Nhấn **Try it out**
3. Điền body:
   ```json
   {
     "code": "SV00001",
     "password": "<mat-khau>"
   }
   ```
4. Nhấn **Execute**

**Kết quả mong đợi:**
- HTTP status: **200 OK**
- Response body:
  ```json
  {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 3600
  }
  ```

**Ghi chú:** Sao chép giá trị `accessToken` vào clipboard để dùng ở bước 3.

---

### Bước 3 — Authorize *(30 giây)*

**Thao tác:**
1. Nhấn nút **Authorize** ở góc trên phải Swagger UI
2. Trong ô **bearerAuth (http, Bearer)**, dán `accessToken` vào (không cần thêm `Bearer ` phía trước — Swagger tự thêm)
3. Nhấn **Authorize** → nhấn **Close**

**Kết quả mong đợi:**
- Biểu tượng ổ khóa xác nhận đã đăng nhập
- Tất cả endpoint có ổ khóa sẽ tự động gắn `Authorization: Bearer <token>` trong các request tiếp theo

---

### Bước 4 — Xem thông tin tài khoản *(30 giây)*

**Thao tác:**
1. Mở rộng **`GET /api/v1/me`**
2. Nhấn **Try it out** → **Execute**

**Kết quả mong đợi:**
- HTTP status: **200 OK**
- Response body:
  ```json
  {
    "id": 1,
    "code": "SV00001",
    "fullName": "Nguyễn Văn A",
    "role": "STUDENT"
  }
  ```

**Ghi chú:** Xác nhận JWT hoạt động đúng và `role=STUDENT`.

---

### Bước 5 — Xem đợt đăng ký hiện tại *(30 giây)*

**Thao tác:**
1. Mở rộng **`GET /api/v1/registration`**
2. Nhấn **Try it out** → **Execute**

**Kết quả mong đợi:**
- HTTP status: **200 OK**
- Response body:
  ```json
  {
    "id": 1,
    "name": "Đăng ký học kỳ 2026-1",
    "status": "OPEN",
    "startAt": "2026-...",
    "endAt": "2026-..."
  }
  ```

**Ghi chú:** Trạng thái phải là `OPEN` — đây là điều kiện tiên quyết để sinh viên đăng ký được.

---

### Bước 6 — Tra cứu danh sách lớp học phần *(1 phút)*

**Thao tác:**
1. Mở rộng **`GET /api/v1/sections`**
2. Nhấn **Try it out** → **Execute** (không lọc)

**Kết quả mong đợi:**
- HTTP status: **200 OK**
- Trả về **12 lớp học phần** (8 môn học × các lớp)

**Thao tác lọc:**
3. Nhập `hasSeats=true` vào query parameter → **Execute**

**Kết quả mong đợi:**
- Chỉ hiển thị các lớp còn chỗ trống (`enrolled < capacity`)

**Ghi chú:** Để ý `sectionId=11` có `capacity=2` — sẽ dùng để demo SECTION_FULL ở bước 9.

---

### Bước 7 — Đăng ký lớp học phần *(1 phút)*

**Thao tác:**
1. Mở rộng **`POST /api/v1/enrollments`**
2. Nhấn **Try it out**
3. Thêm header **`Idempotency-Key: demo-sv00001-001`**
4. Điền body:
   ```json
   {
     "sectionIds": [3]
   }
   ```
5. Nhấn **Execute**

**Kết quả mong đợi:**
- HTTP status: **201 Created**
- Response body:
  ```json
  {
    "items": [
      {
        "enrollmentId": 1,
        "sectionId": 3,
        "status": "CONFIRMED",
        "createdAt": "2026-..."
      }
    ]
  }
  ```
- **Không có** header `X-Idempotent-Replay`

**Ghi chú:** Ghi lại `enrollmentId=1` để dùng ở bước 10–11.

---

### Bước 8 — Gửi lại cùng Idempotency-Key *(1 phút)*

> Mục đích: Chứng minh hệ thống an toàn với double-submit (mạng chập chờn, người dùng bấm 2 lần).

**Thao tác:**
1. **Giữ nguyên** toàn bộ request từ bước 7 (cùng header `Idempotency-Key: demo-sv00001-001`, cùng body)
2. Nhấn **Execute** lần nữa

**Kết quả mong đợi:**
- HTTP status: **201 Created** (vẫn 201, không phải 409)
- Response body: **giống hệt** bước 7 (cùng `enrollmentId=1`)
- Header phản hồi có: **`X-Idempotent-Replay: true`**

**Ghi chú:** Đây là bằng chứng idempotency hoạt động — cùng key trả về cùng kết quả, không tạo enrollment trùng.

---

### Bước 9 — Gặp lỗi SECTION_FULL *(2 phút)*

> Mục đích: Chứng minh constraint capacity được thực thi đúng, ngay cả khi nhiều request đồng thời.

#### Phương án A — Demo với 2 tài khoản mới (nếu capacity=11 còn 0/2):

**Thao tác:**
1. Mở tab trình duyệt mới, đăng nhập bằng `SV00002`, lấy JWT mới
2. Authorize trên tab mới
3. `POST /enrollments` với `sectionId=11`, Idempotency-Key: `demo-sv00002-001` → **201 Created** (lần 1)
4. Quay lại tab SV00001, `POST /enrollments` với `sectionId=11`, Idempotency-Key: `demo-sv00001-sec11` → **201 Created** (lần 2, capacity đầy)
5. Đăng nhập bằng `SV00003`, thử `POST /enrollments` với `sectionId=11`, Idempotency-Key: `demo-sv00003-001`

**Kết quả mong đợi (SV00003):**
- HTTP status: **409 Conflict**
- Response body:
  ```json
  {
    "code": "SECTION_FULL",
    "message": "Lớp học phần đã hết chỗ",
    "sectionId": 11
  }
  ```

#### Phương án B — Demo nhanh (nếu SV00001 chưa đăng ký sectionId=11):

1. Đăng ký sectionId=11 bằng SV00001 (lần 1) → 201
2. Đăng ký sectionId=11 bằng SV00002 (lần 2) → 201 (capacity đầy)
3. Đăng ký sectionId=11 bằng SV00003 → **409 SECTION_FULL**

**Ghi chú:** Nếu không kịp dùng 3 tài khoản, có thể seed lại với `capacity=1` cho sectionId=11 để demo đơn giản hơn.

---

### Bước 10 — Hủy đăng ký *(30 giây)*

**Thao tác:**
1. Mở rộng **`DELETE /api/v1/enrollments/{enrollmentId}`**
2. Nhấn **Try it out**
3. Nhập `enrollmentId=1` (từ bước 7)
4. Nhấn **Execute**

**Kết quả mong đợi:**
- HTTP status: **204 No Content**
- Body rỗng

**Ghi chú:** Enrollment đã bị xóa khỏi hệ thống. Slot của sectionId=3 được giải phóng.

---

### Bước 11 — Hủy lần hai → 404 *(30 giây)*

> Mục đích: Chứng minh hệ thống xử lý đúng "double delete" — không crash, trả về lỗi rõ ràng.

**Thao tác:**
1. **Giữ nguyên** request từ bước 10 (`enrollmentId=1`)
2. Nhấn **Execute** lần nữa

**Kết quả mong đợi:**
- HTTP status: **404 Not Found**
- Response body:
  ```json
  {
    "code": "ENROLLMENT_NOT_FOUND",
    "message": "Không tìm thấy đăng ký học phần với ID 1"
  }
  ```

**Ghi chú:** Kết thúc demo. Toàn bộ luồng hoàn thành trong < 15 phút.

---

## Tóm tắt luồng demo

| # | Endpoint | Method | HTTP mong đợi | Điểm nổi bật |
|---|----------|--------|--------------|--------------|
| 2 | `/auth/login` | POST | 200 | Lấy JWT |
| 3 | Swagger Authorize | — | — | Gắn Bearer token |
| 4 | `/me` | GET | 200 | role=STUDENT |
| 5 | `/registration` | GET | 200 | status=OPEN |
| 6 | `/sections` | GET | 200 | 12 lớp, lọc hasSeats |
| 7 | `/enrollments` | POST | 201 | Đăng ký thành công |
| 8 | `/enrollments` | POST | 201 | Idempotency replay |
| 9 | `/enrollments` | POST | 409 | SECTION_FULL |
| 10 | `/enrollments/{id}` | DELETE | 204 | Hủy thành công |
| 11 | `/enrollments/{id}` | DELETE | 404 | ENROLLMENT_NOT_FOUND |

---

## Xử lý tình huống bất ngờ

| Tình huống | Cách xử lý |
|-----------|-----------|
| Swagger không load | Kiểm tra `docker compose ps` — api có đang running không? |
| Login trả về 401 | Seed chưa đúng mật khẩu — kiểm tra biến `pw` khi seed |
| `/registration` trả về 404 | Seed chưa tạo đợt đăng ký, hoặc status không phải OPEN |
| POST enrollment trả về 403 | JWT hết hạn — đăng nhập lại lấy token mới |
| Capacity sectionId=11 không phải 2 | Seed lại từ đầu: `docker compose down -v && docker compose up -d` |
| 409 xuất hiện sớm hơn dự kiến | Ai đó đã đăng ký trước — xác nhận DB sạch trước demo |
