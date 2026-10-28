# Hướng dẫn triển khai 4 máy qua Tailscale

> Tài liệu này mô tả cách triển khai hệ thống đăng ký học phần VNU trên 4 máy vật lý/VM kết nối với nhau qua mạng Tailscale riêng tư.

---

## 1. Kiến trúc tổng quan

![Bốn máy trong mạng Tailscale: client gọi API qua cổng 8080, chỉ backend vào cổng 5432 của DB](assets/luong-trien-khai.gif)

```
┌─────────────────────────────────────────────────────────────┐
│                      Tailscale VPN (tailnet)                │
│                                                             │
│  ┌───────────────┐        :5432        ┌────────────────┐  │
│  │   máy-db      │◄────────────────────│  máy-backend   │  │
│  │ 100.x.x.1     │  (chỉ từ backend)   │  100.x.x.2     │  │
│  │  PostgreSQL   │                     │  Spring Boot   │  │
│  └───────────────┘                     └───────┬────────┘  │
│                                                │ :8080      │
│                              ┌─────────────────┴──────────┐│
│                              │                            ││
│                   ┌──────────┴────┐          ┌────────────┴┐│
│                   │  máy-client-1 │          │ máy-client-2 ││
│                   │  100.x.x.3   │          │  100.x.x.4  ││
│                   │  SV00001–500  │          │ SV00501–1000 ││
│                   │     (k6)      │          │    (k6)     ││
│                   └───────────────┘          └─────────────┘│
└─────────────────────────────────────────────────────────────┘
```

| Máy | Tailscale IP | Vai trò |
|-----|-------------|---------|
| `máy-db` | `100.x.x.1` | Chỉ chạy PostgreSQL 16 |
| `máy-backend` | `100.x.x.2` | Chạy Spring Boot API |
| `máy-client-1` | `100.x.x.3` | Sinh tải k6, 500 tài khoản SV00001–SV00500 |
| `máy-client-2` | `100.x.x.4` | Sinh tải k6, 500 tài khoản SV00501–SV01000 |

> **Nguyên tắc bảo mật cốt lõi:** Cổng 5432 (PostgreSQL) chỉ mở với máy-backend. Máy-client không được phép kết nối thẳng vào DB.

---

## 2. Yêu cầu trước khi bắt đầu

- [x] **Tailscale đã cài** trên cả 4 máy (`tailscale version` báo OK)
- [x] **4 máy đã join cùng một tailnet** (`tailscale status` thấy đủ 4 peer)
- [x] Ghi lại Tailscale IP của từng máy: `tailscale ip -4`
- [x] Docker + Docker Compose v2 đã cài trên `máy-db` và `máy-backend`
- [x] k6 đã cài trên `máy-client-1` và `máy-client-2`
- [x] Repo đã clone được (SSH key hoặc HTTPS token đã cấu hình)

---

## 3. Cấu hình máy-db

### 3.1 Clone repo và tạo `.env`

```bash
git clone <repo-url> VNU-dangkyhocphan-premium
cd VNU-dangkyhocphan-premium
cp .env.example .env
```

### 3.2 Chỉnh `.env` trên máy-db

Chỉ cần quan tâm các biến liên quan đến PostgreSQL:

```dotenv
# Thay <tailscale-ip-db> bằng IP thực của máy-db, ví dụ: 100.64.1.1
DB_BIND_ADDR=<tailscale-ip-db>
DB_PORT=5432

POSTGRES_DB=dkhp
POSTGRES_USER=dkhp_app
POSTGRES_PASSWORD=<mat-khau-postgres-manh>
DB_USER=dkhp_api
DB_PASSWORD=<mat-khau-api-user-manh>

# Các biến sau không dùng trên máy-db nhưng để tránh lỗi khi validate
JWT_SECRET=placeholder-not-used-on-db-machine
API_BIND_ADDR=127.0.0.1
API_PORT=8080
HEALTH_ALLOWED_IPS=127.0.0.1
```

> **Quan trọng:** Đặt `DB_BIND_ADDR=<tailscale-ip-db>` khiến Docker bind cổng 5432 lên giao diện Tailscale thay vì `127.0.0.1`. Nhờ đó máy-backend trên tailnet có thể kết nối, nhưng internet công cộng không thể.

### 3.3 Khởi động PostgreSQL

```bash
# Chỉ chạy service postgres, không chạy api
docker compose up -d postgres

# Kiểm tra postgres đã healthy
docker compose ps
```

Kết quả mong đợi: cột `STATUS` hiển thị `healthy`.

### 3.4 Kiểm tra port

**Từ máy-backend** (kết nối thành công):
```bash
nc -zv <tailscale-ip-db> 5432
# Connection to 100.x.x.1 5432 port [tcp/postgresql] succeeded!
```

**Từ máy-client-1 hoặc máy-client-2** (kết nối bị từ chối):
```bash
nc -zv <tailscale-ip-db> 5432
# nc: connect to 100.x.x.1 port 5432 (tcp) failed: Connection refused
```

Nếu client vẫn kết nối được, hãy kiểm tra lại [Tailscale ACL](#7-tailscale-acl-mẫu).

---

## 4. Cấu hình máy-backend

### 4.1 Clone repo và tạo `.env`

```bash
git clone <repo-url> VNU-dangkyhocphan-premium
cd VNU-dangkyhocphan-premium
cp .env.example .env
```

### 4.2 Chỉnh `.env` trên máy-backend

```dotenv
# ── PostgreSQL (nằm trên máy-db) ─────────────────────────────
POSTGRES_DB=dkhp
POSTGRES_USER=dkhp_app
POSTGRES_PASSWORD=<mat-khau-postgres-manh>   # khớp với máy-db
DB_USER=dkhp_api
DB_PASSWORD=<mat-khau-api-user-manh>         # khớp với máy-db

# DB_BIND_ADDR trỏ sang máy-db (không phải máy này)
# Biến này dùng để backend biết kết nối vào đâu
DB_BIND_ADDR=<tailscale-ip-db>
DB_PORT=5432

# ── Spring Boot API ───────────────────────────────────────────
# API lắng nghe trên Tailscale IP của máy-backend
API_BIND_ADDR=<tailscale-ip-backend>
API_PORT=8080

# JWT
JWT_SECRET=<openssl rand -base64 48>

# Chỉ cho phép máy-backend và máy dev gọi /health
HEALTH_ALLOWED_IPS=<tailscale-ip-backend>,<tailscale-ip-dev>

# Giới hạn kết nối pool
DB_POOL_SIZE=20
RATE_LIMIT_IP=600
RATE_LIMIT_ACCOUNT=120
RATE_LIMIT_LOGIN=10
```

> **Lưu ý về `SPRING_DATASOURCE_URL`:** Trong `docker-compose.yml`, service `api` dùng hostname nội bộ `postgres` để kết nối DB. Vì trên máy-backend **không chạy** service `postgres`, cần tách riêng cấu hình. Xem mục 4.3.

### 4.3 Khởi động API — chế độ DB ngoài (remote DB)

Vì PostgreSQL nằm trên máy-db (không phải `localhost`), cần override `SPRING_DATASOURCE_URL`:

```bash
# Build image trước
docker compose build api

# Chạy với DB trỏ sang máy-db qua Tailscale
SPRING_DATASOURCE_URL="jdbc:postgresql://<tailscale-ip-db>:5432/dkhp" \
  docker compose up -d api
```

Hoặc thêm biến này vào file `.env`:

```dotenv
# Ghi đè URL để trỏ sang máy-db (thêm vào cuối .env)
SPRING_DATASOURCE_URL=jdbc:postgresql://<tailscale-ip-db>:5432/dkhp
```

Sau đó chạy bình thường:

```bash
docker compose up -d api
docker compose logs -f api
```

### 4.4 Kiểm tra API

```bash
# Health check (phải chạy từ máy-backend hoặc máy được liệt kê trong HEALTH_ALLOWED_IPS)
curl http://<tailscale-ip-backend>:8080/api/v1/health

# Kết quả mong đợi:
# {"status":"UP","db":"UP"}
```

```bash
# Từ máy-client: gọi API bình thường phải thành công
curl http://<tailscale-ip-backend>:8080/api/v1/sections
# Trả về danh sách lớp học phần (200 OK)
```

---

## 5. Cấu hình máy-client-1 và máy-client-2

### 5.1 Cài k6

```bash
# Ubuntu/Debian
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
     --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
     | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# macOS
brew install k6

# Kiểm tra
k6 version
```

### 5.2 Clone repo (chỉ cần thư mục `load/`)

```bash
git clone <repo-url> VNU-dangkyhocphan-premium
cd VNU-dangkyhocphan-premium
```

### 5.3 Chạy k6 trên máy-client-1 (SV00001–SV00500)

```bash
k6 run \
  -e BASE_URL="http://<tailscale-ip-backend>:8080/api/v1" \
  -e VU_START=1 \
  -e VU_END=500 \
  --out json=load/results/result_client1_$(date +%Y%m%d_%H%M%S).json \
  load/k6/full_scenario.js
```

### 5.4 Chạy k6 trên máy-client-2 (SV00501–SV01000)

```bash
k6 run \
  -e BASE_URL="http://<tailscale-ip-backend>:8080/api/v1" \
  -e VU_START=501 \
  -e VU_END=1000 \
  --out json=load/results/result_client2_$(date +%Y%m%d_%H%M%S).json \
  load/k6/full_scenario.js
```

> **Đồng bộ thời gian:** Khởi động cả 2 lệnh gần như cùng lúc để đạt 1000 VU đồng thời. Có thể dùng `at` hoặc SSH đa phiên.

---

## 6. Bảo mật — Tại sao cổng DB không mở cho client?

| Lý do | Giải thích |
|-------|-----------|
| **Nguyên tắc least privilege** | Client (k6) chỉ cần gọi HTTP API, không cần truy cập thẳng DB. Mở cổng 5432 là bề mặt tấn công không cần thiết. |
| **Ngăn SQL injection trực tiếp** | Nếu script k6 bị thao túng hoặc rò rỉ thông tin, kẻ tấn công không thể kết nối DB dù có IP Tailscale của client. |
| **Kiểm soát qua API layer** | Mọi truy cập dữ liệu đều đi qua Spring Boot — nơi có rate limiting, JWT auth, idempotency, và audit log. |
| **Giảm thiểu ảnh hưởng khi máy client bị xâm phạm** | Máy client chỉ có thể gây ra tải HTTP, không thể đọc/ghi DB trực tiếp. |

Trong `docker-compose.yml`, dòng sau đảm bảo DB chỉ bind đúng địa chỉ được phép:

```yaml
ports:
  - "${DB_BIND_ADDR:-127.0.0.1}:${DB_PORT:-5432}:5432"
```

Khi `DB_BIND_ADDR=<tailscale-ip-db>`, chỉ các máy trong tailnet **và được Tailscale ACL cho phép** mới kết nối được cổng này.

---

## 7. Kiểm tra nhanh (Quick Verification)

### Từ máy-client: API được, DB bị từ chối

```bash
BACKEND_IP=<tailscale-ip-backend>
DB_IP=<tailscale-ip-db>

# Gọi API — kỳ vọng thành công (200)
curl -s -o /dev/null -w "%{http_code}" \
  http://${BACKEND_IP}:8080/api/v1/sections
# Kết quả: 200

# Kết nối thẳng DB — kỳ vọng thất bại
nc -zv -w 3 ${DB_IP} 5432
# Kết quả: Connection refused hoặc timeout
```

### Từ máy-backend: DB phải kết nối được

```bash
# Kiểm tra kết nối từ trong container api
docker compose exec api \
  sh -c "nc -zv <tailscale-ip-db> 5432"
# Kết quả: succeeded!

# Hoặc kiểm tra datasource qua health
curl http://<tailscale-ip-backend>:8080/api/v1/health
# {"status":"UP","db":"UP"}
```

---

## 8. Tailscale ACL mẫu

Thêm policy sau vào Tailscale Admin Console → **Access Controls** để đảm bảo máy-client không thể kết nối cổng 5432:

```json
{
  "tagOwners": {
    "tag:db":      ["autogroup:admin"],
    "tag:backend": ["autogroup:admin"],
    "tag:client":  ["autogroup:admin"]
  },

  "acls": [
    {
      "action": "accept",
      "comment": "Backend kết nối được PostgreSQL trên DB",
      "src":  ["tag:backend"],
      "dst":  ["tag:db:5432"]
    },
    {
      "action": "accept",
      "comment": "Client kết nối được API trên backend",
      "src":  ["tag:client"],
      "dst":  ["tag:backend:8080"]
    },
    {
      "action": "accept",
      "comment": "Admin có thể SSH vào tất cả máy",
      "src":  ["autogroup:admin"],
      "dst":  ["*:22"]
    }
  ],

  "tests": [
    {
      "src":    "tag:client",
      "accept": ["tag:backend:8080"],
      "deny":   ["tag:db:5432"]
    },
    {
      "src":    "tag:backend",
      "accept": ["tag:db:5432"]
    }
  ]
}
```

### Gán tag cho từng máy

```bash
# Trên máy-db
tailscale up --advertise-tags=tag:db

# Trên máy-backend
tailscale up --advertise-tags=tag:backend

# Trên máy-client-1 và máy-client-2
tailscale up --advertise-tags=tag:client
```

> **Sau khi áp dụng ACL**, chạy lại bước [kiểm tra nhanh](#7-kiểm-tra-nhanh-quick-verification) để xác nhận policy hoạt động đúng.

---

## 9. Tóm tắt thứ tự triển khai

```
1. Tailscale: 4 máy join tailnet, gán tag
2. Áp dụng Tailscale ACL
3. máy-db:      git clone → .env → docker compose up -d postgres
4. máy-backend: git clone → .env → docker compose build api → docker compose up -d api
5. Kiểm tra health từ máy-backend
6. máy-client-1 & 2: git clone → cài k6 → chạy full_scenario.js
```
