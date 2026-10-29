# Ma trận kiểm thử

Các ca kiểm thử thủ công cho luồng nghiệp vụ chính, chạy bằng `curl` trên DB vừa seed từ `docker/seed/seed_dev.sql`. Số id lớp dưới đây lấy theo seed đó.

## Chuẩn bị

```bash
docker compose up -d
docker compose exec -T postgres psql -U dkhp_app -d dkhp -v pw="$DEV_PASSWORD" -f - < docker/seed/seed_dev.sql
export BASE_URL="http://localhost:8080/api/v1"
```

`DEV_PASSWORD` là mật khẩu đã truyền vào `-v pw=` khi seed. Hai hàm hỗ trợ lấy JWT và gửi đăng ký:

```bash
login() {
  curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
    -d "{\"code\":\"$1\",\"password\":\"$DEV_PASSWORD\"}" | jq -r '.accessToken'
}
enroll() {  # enroll <jwt> <idempotency-key> <json-array>
  curl -s -i -X POST "$BASE_URL/enrollments" -H "Authorization: Bearer $1" \
    -H "Content-Type: application/json" -H "Idempotency-Key: $2" -d "{\"sectionIds\":$3}"
}
export JWT=$(login SV00001)
```

Mọi lỗi có dạng `{"code":"...","message":"...","details":...}`.

## Danh sách ca kiểm thử

| Mã | Nội dung | Điều kiện | Kết quả mong đợi |
| --- | --- | --- | --- |
| TC01 | Sinh viên đăng nhập | Đợt `OPEN`, đang trong giờ đăng nhập | 200, có `accessToken`, `tokenType` = `Bearer`, `expiresIn` = 3600 |
| TC02 | Sinh viên đăng nhập ngoài giờ | `login_opens_at` đặt ở tương lai | 403 `LOGIN_NOT_OPEN` |
| TC03 | Admin đăng nhập khi đợt đang mở | Đợt `OPEN`, trong `[opensAt, closesAt)` | 403 `ADMIN_LOGIN_LOCKED` |
| TC04 | Đăng ký một lớp | SV00001 chưa đăng ký gì, lớp 1 còn chỗ | 201, `items[0].sectionId` = 1, không có header `X-Idempotent-Replay` |
| TC05 | Lớp đầy | SV00002 và SV00003 đã đăng ký lớp 11 (sĩ số 2) | 409 `SECTION_FULL` |
| TC06 | Đăng ký lại lớp đã có | Sau TC04, gửi lớp 1 với key mới | 409 `SECTION_ALREADY_ENROLLED` |
| TC07 | Lớp khác cùng học phần | Sau TC04, gửi lớp 2 (cũng INT3001) | 409 `COURSE_ALREADY_ENROLLED` |
| TC08 | Trùng ca | Sau TC04, gửi lớp 13 (cùng ca T2 ca 1, T4 ca 1 với lớp 1) | 422 `SESSION_CONFLICT` |
| TC09 | Vượt tín chỉ | SV00004 gửi một lần các lớp 1, 3, 5, 6, 7, 9, 18 (22 tín chỉ, hạn mức 20) | 422 `CREDIT_LIMIT_EXCEEDED`, không lớp nào được ghi |
| TC10 | Gửi lại cùng key, cùng body | Gửi hai lần cùng key | Lần hai 201, body giống lần một, có `X-Idempotent-Replay: true` |
| TC11 | Cùng key, khác body | Dùng lại key của TC10 với lớp khác | 409 `IDEMPOTENCY_KEY_REUSED` |
| TC12 | Hủy đăng ký | `ENROLL_ID` lấy từ TC04 | 204, `enrolledCount` của lớp 1 giảm 1 |
| TC13 | Nhiều người tranh cùng lớp | Chạy `load/scripts/concurrent_test.sh` | Không vượt sĩ số, không lượt trùng, bất biến PASS |
| TC14 | Hủy lần hai | Sau TC12 | 404 `ENROLLMENT_NOT_FOUND` |

## Lệnh chạy

TC01:

```bash
curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
  -d "{\"code\":\"SV00001\",\"password\":\"$DEV_PASSWORD\"}" | jq .
```

TC02: dời giờ mở đăng nhập sang ngày hôm sau, chạy lại lệnh TC01, sau đó khôi phục giá trị cũ:

```bash
docker compose exec postgres psql -U dkhp_app -d dkhp -c \
  "UPDATE registration_period SET login_opens_at = now() + interval '1 day' WHERE term = '2026-1';"
```

Khôi phục sau khi chạy TC02:

```bash
docker compose exec postgres psql -U dkhp_app -d dkhp -c   "UPDATE registration_period SET login_opens_at = now() - interval '1 hour' WHERE term = '2026-1';"
```

TC03:

```bash
curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
  -d "{\"code\":\"admin\",\"password\":\"$DEV_PASSWORD\"}" | jq .
```

TC04 (lưu lại `enrollmentId`):

```bash
export ENROLL_ID=$(enroll "$JWT" tc04 '[1]' | sed -n '/^{/p' | jq -r '.items[0].enrollmentId')
```

TC05:

```bash
enroll "$(login SV00002)" tc05-a '[11]'
enroll "$(login SV00003)" tc05-b '[11]'
enroll "$JWT" tc05 '[11]'
```

TC06 đến TC08:

```bash
enroll "$JWT" tc06 '[1]'
enroll "$JWT" tc07 '[2]'
enroll "$JWT" tc08 '[13]'
```

TC09:

```bash
enroll "$(login SV00004)" tc09 '[1,3,5,6,7,9,18]'
```

TC10 và TC11:

```bash
enroll "$JWT" tc10 '[3]'
enroll "$JWT" tc10 '[3]'   # lần hai: xem header X-Idempotent-Replay
enroll "$JWT" tc10 '[5]'   # TC11
```

TC12 và TC14 (chạy cùng một lệnh hai lần):

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE "$BASE_URL/enrollments/$ENROLL_ID" -H "Authorization: Bearer $JWT"
```

## Thứ tự

Chạy theo thứ tự TC01, TC03, TC04 đến TC12, TC14, TC02. TC06, TC07, TC08, TC12 cần TC04 chạy trước; TC11 cần TC10; TC14 cần TC12. TC02 thay đổi giờ đăng nhập nên chạy cuối cùng. TC13 chạy riêng, xem `load/README.md`.

## Kết quả

| Mã | Kết quả (Đạt / Không đạt / Bỏ qua) | Ngày chạy | Ghi chú |
| --- | --- | --- | --- |
| TC01 | | | |
| TC02 | | | |
| TC03 | | | |
| TC04 | | | |
| TC05 | | | |
| TC06 | | | |
| TC07 | | | |
| TC08 | | | |
| TC09 | | | |
| TC10 | | | |
| TC11 | | | |
| TC12 | | | |
| TC13 | | | |
| TC14 | | | |
