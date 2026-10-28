# Ma trận kiểm thử — VNU Đăng ký học phần

> **Phạm vi:** Kiểm thử thủ công / tích hợp cho các luồng nghiệp vụ cốt lõi.
> **Biến môi trường:** `BASE_URL=http://localhost:8080/api/v1`
> **Yêu cầu chung:** Server đang chạy, DB đã được seed dữ liệu mẫu (xem `scripts/seed.sql`).

---

## Quy ước ký hiệu

| Ký hiệu | Ý nghĩa |
|---------|---------|
| ✅ | Đạt |
| ❌ | Không đạt |
| ⏭️ | Bỏ qua |
| `$JWT_STUDENT` | JWT của tài khoản sinh viên test (lấy từ TC01) |
| `$JWT_ADMIN` | JWT của tài khoản admin test |
| `$ENROLL_ID` | `enrollmentId` lấy từ TC04 |

---

## Ma trận kiểm thử

| Mã TC | Mô tả | Endpoint | Điều kiện tiên quyết | Kết quả mong đợi | Lệnh / Script | Kết quả | Ngày chạy |
|-------|-------|----------|----------------------|-----------------|---------------|---------|-----------|
| **TC01** | Đăng nhập sinh viên thành công | `POST /auth/login` | DB có tài khoản sinh viên `22000001`/`Test@1234`; đang trong khung giờ `loginOpensAt`–`closesAt`; đợt đăng ký ở trạng thái OPEN | HTTP 200; body chứa `accessToken`, `tokenType: "Bearer"`, `expiresIn > 0` | `curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" -d '{"code":"22000001","password":"Test@1234"}' \| jq .` | | |
| **TC02** | Đăng nhập ngoài giờ (LOGIN_NOT_OPEN) | `POST /auth/login` | DB có tài khoản sinh viên `22000001`; thời gian server **trước** `loginOpensAt` hoặc **sau** `closesAt` (có thể mock bằng cách set `loginOpensAt` ở tương lai) | HTTP 403; body `{"code":"LOGIN_NOT_OPEN","message":"...","details":null}` | `curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" -d '{"code":"22000001","password":"Test@1234"}' \| jq .` | | |
| **TC03** | Admin đăng nhập bị khóa khi đợt OPEN (ADMIN_LOGIN_LOCKED) | `POST /auth/login` | DB có tài khoản admin `admin01`; đợt đăng ký đang ở trạng thái **OPEN**; đang trong khung giờ hợp lệ | HTTP 403; body `{"code":"ADMIN_LOGIN_LOCKED","message":"...","details":null}` | `curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" -d '{"code":"admin01","password":"Admin@1234"}' \| jq .` | | |
| **TC04** | Đăng ký 1 lớp thành công | `POST /enrollments` | Có `$JWT_STUDENT` hợp lệ (từ TC01); đợt đăng ký OPEN; lớp `sectionId=1001` còn chỗ trống; sinh viên chưa đăng ký lớp/học phần này | HTTP 201; body có `items[0].enrollmentId`, `items[0].sectionId == 1001`; header `X-Idempotent-Replay: false` | `curl -s -X POST "$BASE_URL/enrollments" -H "Authorization: Bearer $JWT_STUDENT" -H "Content-Type: application/json" -H "Idempotency-Key: test-tc04-$(date +%s)" -d '{"sectionIds":[1001]}' \| jq .` | | |
| **TC05** | Đăng ký khi lớp đã đầy (SECTION_FULL) | `POST /enrollments` | Có `$JWT_STUDENT` hợp lệ; đợt OPEN; lớp `sectionId=1099` có `enrolledCount == capacity` (seed trước) | HTTP 409; body `{"code":"SECTION_FULL","message":"...","details":null}` | `curl -s -X POST "$BASE_URL/enrollments" -H "Authorization: Bearer $JWT_STUDENT" -H "Content-Type: application/json" -H "Idempotency-Key: test-tc05-$(date +%s)" -d '{"sectionIds":[1099]}' \| jq .` | | |
| **TC06** | Đăng ký trùng lớp (SECTION_ALREADY_ENROLLED) | `POST /enrollments` | Có `$JWT_STUDENT` hợp lệ; đợt OPEN; sinh viên **đã đăng ký** lớp `sectionId=1001` (TC04 đã chạy thành công) | HTTP 409; body `{"code":"SECTION_ALREADY_ENROLLED","message":"...","details":null}` | `curl -s -X POST "$BASE_URL/enrollments" -H "Authorization: Bearer $JWT_STUDENT" -H "Content-Type: application/json" -H "Idempotency-Key: test-tc06-$(date +%s)" -d '{"sectionIds":[1001]}' \| jq .` | | |
| **TC07** | Đăng ký trùng học phần ở lớp khác (COURSE_ALREADY_ENROLLED) | `POST /enrollments` | Có `$JWT_STUDENT` hợp lệ; đợt OPEN; sinh viên đã đăng ký lớp `1001` (courseCode `INT3306`); lớp `sectionId=1002` cùng courseCode `INT3306` nhưng khác lớp | HTTP 409; body `{"code":"COURSE_ALREADY_ENROLLED","message":"...","details":null}` | `curl -s -X POST "$BASE_URL/enrollments" -H "Authorization: Bearer $JWT_STUDENT" -H "Content-Type: application/json" -H "Idempotency-Key: test-tc07-$(date +%s)" -d '{"sectionIds":[1002]}' \| jq .` | | |
| **TC08** | Đăng ký trùng ca học (SESSION_CONFLICT) | `POST /enrollments` | Có `$JWT_STUDENT` hợp lệ; đợt OPEN; sinh viên đã đăng ký lớp `1001` (session bitmask có bit 1); lớp `sectionId=1003` có session bitmask giao với lớp đã đăng ký | HTTP 422; body `{"code":"SESSION_CONFLICT","message":"...","details":null}` | `curl -s -X POST "$BASE_URL/enrollments" -H "Authorization: Bearer $JWT_STUDENT" -H "Content-Type: application/json" -H "Idempotency-Key: test-tc08-$(date +%s)" -d '{"sectionIds":[1003]}' \| jq .` | | |
| **TC09** | Đăng ký vượt giới hạn tín chỉ (CREDIT_LIMIT_EXCEEDED) | `POST /enrollments` | Có `$JWT_STUDENT` hợp lệ; đợt OPEN; sinh viên đã đăng ký đủ `creditLimit` tín chỉ (seed đăng ký nhiều lớp trước); lớp `sectionId=1010` còn chỗ và không trùng ca | HTTP 422; body `{"code":"CREDIT_LIMIT_EXCEEDED","message":"...","details":null}` | `curl -s -X POST "$BASE_URL/enrollments" -H "Authorization: Bearer $JWT_STUDENT" -H "Content-Type: application/json" -H "Idempotency-Key: test-tc09-$(date +%s)" -d '{"sectionIds":[1010]}' \| jq .` | | |
| **TC10** | Gửi lại cùng Idempotency-Key — phát lại 201 (idempotent replay) | `POST /enrollments` | TC04 đã thành công với key `IDEMPOTENCY_KEY_TC10`; sử dụng **chính xác cùng key và payload** | HTTP 201; body giống hệt TC04; header `X-Idempotent-Replay: true` | `export IK="test-tc10-fixed-key"; curl -s -X POST "$BASE_URL/enrollments" -H "Authorization: Bearer $JWT_STUDENT" -H "Content-Type: application/json" -H "Idempotency-Key: $IK" -d '{"sectionIds":[1001]}' \| jq . && echo "---replay---" && curl -s -X POST "$BASE_URL/enrollments" -H "Authorization: Bearer $JWT_STUDENT" -H "Content-Type: application/json" -H "Idempotency-Key: $IK" -d '{"sectionIds":[1001]}' -v 2>&1 \| grep -E "X-Idempotent|HTTP"` | | |
| **TC11** | Gửi lại cùng Idempotency-Key nhưng khác sectionIds (IDEMPOTENCY_KEY_REUSED) | `POST /enrollments` | TC10 đã dùng key `test-tc10-fixed-key` với payload `{"sectionIds":[1001]}`; tái sử dụng key đó với payload **khác** | HTTP 409; body `{"code":"IDEMPOTENCY_KEY_REUSED","message":"...","details":null}` | `curl -s -X POST "$BASE_URL/enrollments" -H "Authorization: Bearer $JWT_STUDENT" -H "Content-Type: application/json" -H "Idempotency-Key: test-tc10-fixed-key" -d '{"sectionIds":[1005]}' \| jq .` | | |
| **TC12** | Hủy đăng ký thành công | `DELETE /enrollments/{enrollmentId}` | Có `$JWT_STUDENT` hợp lệ; đợt OPEN; `$ENROLL_ID` là enrollmentId hợp lệ thuộc sinh viên này (từ TC04) | HTTP 204; không có nội dung trả về; đăng ký bị xóa khỏi DB | `curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE_URL/enrollments/$ENROLL_ID" -H "Authorization: Bearer $JWT_STUDENT"` | | |
| **TC13** | Kiểm thử tải đồng thời (concurrency stress test) | `POST /enrollments` | *(Ngoài phạm vi kiểm thử thủ công đơn lẻ — xem `load/scripts/` và T3)* | Không có race condition; `enrolledCount` không vượt `capacity`; không có bản ghi đăng ký trùng | *(Ngoài phạm vi kiểm thử thủ công đơn lẻ — xem `load/scripts/` và T3)* | N/A | N/A |
| **TC14** | Hủy lần hai trả 404 (ENROLLMENT_NOT_FOUND) | `DELETE /enrollments/{enrollmentId}` | TC12 đã hủy thành công `$ENROLL_ID`; gọi lại DELETE với cùng `$ENROLL_ID` | HTTP 404; body `{"code":"ENROLLMENT_NOT_FOUND","message":"...","details":null}` | `curl -s -X DELETE "$BASE_URL/enrollments/$ENROLL_ID" -H "Authorization: Bearer $JWT_STUDENT" \| jq .` | | |

---

## Hướng dẫn thực thi

### 1. Chuẩn bị môi trường

```bash
# Khởi động server
./mvnw spring-boot:run

# Seed dữ liệu mẫu
psql -U postgres -d vnu_registration -f scripts/seed.sql

# Đặt biến môi trường
export BASE_URL="http://localhost:8080/api/v1"
```

### 2. Lấy JWT sinh viên (TC01)

```bash
export JWT_STUDENT=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"code":"22000001","password":"Test@1234"}' \
  | jq -r '.accessToken')
echo "JWT_STUDENT=$JWT_STUDENT"
```

### 3. Lấy enrollmentId sau TC04

```bash
export ENROLL_ID=$(curl -s -X POST "$BASE_URL/enrollments" \
  -H "Authorization: Bearer $JWT_STUDENT" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-tc04-initial" \
  -d '{"sectionIds":[1001]}' \
  | jq -r '.items[0].enrollmentId')
echo "ENROLL_ID=$ENROLL_ID"
```

### 4. Thứ tự thực thi khuyến nghị

```
TC01 → TC02 → TC03 → TC04 → TC05 → TC06 → TC07 → TC08 → TC09
     → TC10 → TC11 → TC12 → TC14
```

> [!IMPORTANT]
> TC06 và TC07 phụ thuộc vào TC04 đã thành công.
> TC11 phụ thuộc vào TC10 đã thực thi trước.
> TC14 phụ thuộc vào TC12 đã thực thi trước.

> [!NOTE]
> TC02 và TC03 yêu cầu thay đổi dữ liệu DB hoặc thời gian hệ thống để mô phỏng điều kiện ngoài giờ / đợt OPEN. Có thể dùng lệnh SQL:
> ```sql
> -- Mô phỏng TC02: đẩy loginOpensAt về tương lai
> UPDATE registration SET login_opens_at = NOW() + INTERVAL '1 day';
> -- Khôi phục
> UPDATE registration SET login_opens_at = NOW() - INTERVAL '1 hour';
> ```

> [!WARNING]
> TC13 yêu cầu môi trường Docker với nhiều container client đồng thời. Xem `load/scripts/` và tài liệu T3 để biết chi tiết cấu hình.

---

## Tóm tắt coverage

| Nhóm | TC | Trạng thái |
|------|----|-----------|
| Xác thực | TC01, TC02, TC03 | |
| Đăng ký — thành công | TC04 | |
| Đăng ký — lỗi 409 | TC05, TC06, TC07, TC11 | |
| Đăng ký — lỗi 422 | TC08, TC09 | |
| Idempotency | TC10, TC11 | |
| Hủy đăng ký | TC12, TC14 | |
| Kiểm thử tải | TC13 | Ngoài phạm vi |
