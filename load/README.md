# Hướng dẫn Kiểm thử Tải và Đo Hiệu năng (k6)

Thư mục này chứa kịch bản thử tải và script kiểm thử đồng thời cho hệ thống Đăng ký học phần VNU.

## 1. Lựa chọn công cụ: k6

Nhóm chọn **k6** (Grafana k6) làm công cụ thử tải vì:
- Viết kịch bản bằng JavaScript hiện đại, dễ cấu hình và mở rộng.
- Tiêu tốn ít tài nguyên CPU/RAM hơn nhiều so với JMeter trên máy client khi phát tải 1.000 VUs.
- Hỗ trợ cơ chế ramping VUs trực quan và kiểm soát chính xác thông lượng.
- Sinh báo cáo JSON/CSV chi tiết phục vụ phân tích độ trễ (p90, p95, p99), CPU, RAM.

## 2. Chuẩn bị môi trường (từ máy trống)

### 2.1. Cài đặt k6

- **Windows (Chocolatey / Winget / Binary):**
  ```powershell
  winget install k6 --source winget
  # hoặc choco install k6
  ```
- **Linux (Ubuntu/Debian):**
  ```bash
  sudo gpg -k
  sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D34EE24C
  echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
  sudo apt-get update && sudo apt-get install k6
  ```

### 2.2. Chuẩn bị Cơ sở dữ liệu và Dữ liệu mẫu

Trên máy chủ cơ sở dữ liệu:
```bash
docker compose up -d postgres
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v pw='password123' -v students=1000 -v period_status=OPEN -f - < docker/seed/seed_dev.sql
```

## 3. Các kịch bản chạy tải

### 3.1. Đo riêng bước Đăng nhập (`load/k6/login.js`)
Bước này đo lường tải CPU máy chủ khi băm mật khẩu bcrypt và phát hành JWT:
```bash
k6 run \
  -e BASE_URL=http://localhost:8080/api/v1 \
  -e STUDENT_PASSWORD=password123 \
  -e VUS=100 \
  -e TOTAL_REQUESTS=1000 \
  load/k6/login.js
```

### 3.2. Kịch bản tổng thể đầy đủ (`load/k6/full_scenario.js`)
Giả lập 1.000 người dùng ảo và 5.000 requests (đăng nhập -> xem đợt -> xem lớp -> đăng ký idempotency -> hủy):

```bash
k6 run \
  -e BASE_URL=http://localhost:8080/api/v1 \
  -e STUDENT_PASSWORD=password123 \
  -e VUS=1000 \
  --out json=load/results/result_$(date +%Y%m%d_%H%M%S).json \
  load/k6/full_scenario.js
```

### 3.3. Phân tải trên hai máy khách (mỗi máy 500 tài khoản qua Tailscale)

- **Máy khách 1 (tài khoản SV00001 - SV00500):**
  ```bash
  k6 run \
    -e BASE_URL=http://<tailscale-ip-backend>:8080/api/v1 \
    -e STUDENT_PASSWORD=password123 \
    -e VUS=500 \
    -e ACCOUNT_OFFSET=0 \
    -e ACCOUNT_LIMIT=500 \
    load/k6/full_scenario.js
  ```

- **Máy khách 2 (tài khoản SV00501 - SV01000):**
  ```bash
  k6 run \
    -e BASE_URL=http://<tailscale-ip-backend>:8080/api/v1 \
    -e STUDENT_PASSWORD=password123 \
    -e VUS=500 \
    -e ACCOUNT_OFFSET=500 \
    -e ACCOUNT_LIMIT=500 \
    load/k6/full_scenario.js
  ```

## 4. Kiểm thử tính bất biến sau tải

Sau khi chạy tải xong, chạy script kiểm tra bất biến dữ liệu:
```bash
bash load/scripts/run_invariants.sh
```
Yêu cầu: Tất cả các truy vấn bất biến phải trả về 0 dòng lỗi.

