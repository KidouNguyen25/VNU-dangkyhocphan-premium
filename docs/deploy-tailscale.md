# Hướng dẫn triển khai 4 máy qua Tailscale

Hướng dẫn triển khai hệ thống trên 4 máy (máy thật hoặc VM) kết nối với nhau qua Tailscale.


## 1. Kiến trúc tổng quan

![Bốn máy trong mạng Tailscale: client gọi API qua cổng 8080, chỉ backend vào cổng 5432 của DB](assets/luong-trien-khai.gif)

| Máy | Tailscale IP | Vai trò |
|-----|-------------|---------|
| `máy-db` | `100.x.x.1` | Chỉ chạy PostgreSQL 16 |
| `máy-backend` | `100.x.x.2` | Chạy Spring Boot API |
| `máy-client-1` | `100.x.x.3` | Sinh tải k6, 500 tài khoản SV00001–SV00500 |
| `máy-client-2` | `100.x.x.4` | Sinh tải k6, 500 tài khoản SV00501–SV01000 |

Cổng 5432 (PostgreSQL) chỉ mở cho máy-backend. Máy client không kết nối trực tiếp vào DB.


## 2. Yêu cầu trước khi bắt đầu

- Tailscale đã cài trên cả 4 máy (`tailscale version` báo OK)
- 4 máy đã join cùng một tailnet (`tailscale status` thấy đủ 4 peer)
- Ghi lại Tailscale IP của từng máy: `tailscale ip -4`
- Docker và Docker Compose v2 đã cài trên `máy-db`; JDK 17 trở lên đã cài trên `máy-backend`
- k6 đã cài trên `máy-client-1` và `máy-client-2`
- Repo đã clone được (SSH key hoặc HTTPS token đã cấu hình)


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

Đặt `DB_BIND_ADDR=<tailscale-ip-db>` để Docker bind cổng 5432 lên địa chỉ Tailscale thay vì `127.0.0.1`. Máy trong tailnet kết nối được, Internet công cộng thì không.

### 3.3 Khởi động PostgreSQL

```bash
# Chỉ chạy service postgres, không chạy api
docker compose up -d postgres

# Kiểm tra postgres đã healthy
docker compose ps
```

Cột `STATUS` phải là `healthy`.

### 3.4 Kiểm tra port

Từ máy-backend (kết nối được):
```bash
nc -zv <tailscale-ip-db> 5432
# Connection to 100.x.x.1 5432 port [tcp/postgresql] succeeded!
```

Từ máy client (bị chặn):
```bash
nc -zv <tailscale-ip-db> 5432
# nc: connect to 100.x.x.1 port 5432 (tcp) failed: Connection refused
```

Nếu client vẫn kết nối được, kiểm tra lại [Tailscale ACL](#7-tailscale-acl-mẫu).


## 4. Cấu hình máy-backend

### 4.1 Clone repo

```bash
git clone <repo-url> VNU-dangkyhocphan-premium
cd VNU-dangkyhocphan-premium
```

### 4.2 Chạy backend

Trong `docker-compose.yml`, service `api` gắn cứng `SPRING_DATASOURCE_URL` vào service `postgres` trên cùng máy và phụ thuộc vào service đó. Vì vậy trên máy-backend không dùng được `docker compose up api`. Chạy Spring Boot trực tiếp và cấu hình qua biến môi trường (xem `src/main/resources/application.yml`):

```bash
export DB_URL="jdbc:postgresql://<tailscale-ip-db>:5432/dkhp"
export DB_USER=dkhp_api
export DB_PASSWORD=<mat-khau-api-user>    # trùng với máy-db
export SERVER_ADDRESS=<tailscale-ip-backend>
export SERVER_PORT=8080
export JWT_SECRET="$(openssl rand -base64 48)"
export HEALTH_ALLOWED_IPS=<tailscale-ip-backend>,<tailscale-ip-dev>
export DB_POOL_SIZE=20
export RATE_LIMIT_IP=600
export RATE_LIMIT_ACCOUNT=120
export RATE_LIMIT_LOGIN=10

./mvnw spring-boot:run
```

Nếu muốn chạy backend bằng Docker trên máy này, cần sửa `docker-compose.yml` để `SPRING_DATASOURCE_URL` lấy từ biến môi trường và bỏ `depends_on`.

### 4.3 Kiểm tra API

Từ máy-backend hoặc máy có IP trong `HEALTH_ALLOWED_IPS`:

```bash
curl http://<tailscale-ip-backend>:8080/api/v1/health
# {"status":"ok","serverTime":"..."}
```

Từ máy client:

```bash
curl http://<tailscale-ip-backend>:8080/api/v1/sections
# 200, danh sách lớp
```

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

### 5.2 Clone repo

```bash
git clone <repo-url> VNU-dangkyhocphan-premium
cd VNU-dangkyhocphan-premium
```

### 5.3 Chạy k6 trên máy-client-1 (SV00001–SV00500)

```bash
k6 run \
  -e BASE_URL="http://<tailscale-ip-backend>:8080/api/v1" \
  -e STUDENT_PASSWORD="$DEV_PASSWORD" \
  -e VUS=500 -e ACCOUNT_OFFSET=0 -e ACCOUNT_LIMIT=500 \
  --out json=load/results/result_client1_$(date +%Y%m%d_%H%M%S).json \
  load/k6/full_scenario.js
```

### 5.4 Chạy k6 trên máy-client-2 (SV00501–SV01000)

```bash
k6 run \
  -e BASE_URL="http://<tailscale-ip-backend>:8080/api/v1" \
  -e STUDENT_PASSWORD="$DEV_PASSWORD" \
  -e VUS=500 -e ACCOUNT_OFFSET=500 -e ACCOUNT_LIMIT=500 \
  --out json=load/results/result_client2_$(date +%Y%m%d_%H%M%S).json \
  load/k6/full_scenario.js
```

Chạy hai lệnh gần như cùng lúc để có 1000 VU đồng thời, ví dụ bằng `at` hoặc hai phiên SSH. `DEV_PASSWORD` là mật khẩu đã dùng khi seed.


## 6. Lý do không mở cổng DB cho client

Client chỉ cần gọi HTTP API, nên mở cổng 5432 cho client là mở thêm đường tấn công không cần thiết. Mọi truy cập dữ liệu đi qua backend, nơi có xác thực JWT, rate limit và idempotency. Nếu một máy client bị chiếm quyền, máy đó chỉ gửi được request HTTP, không đọc hoặc ghi DB trực tiếp được.

Trong `docker-compose.yml`, dòng sau đảm bảo DB chỉ bind đúng địa chỉ được phép:

```yaml
ports:
  - "${DB_BIND_ADDR:-127.0.0.1}:${DB_PORT:-5432}:5432"
```

Khi `DB_BIND_ADDR=<tailscale-ip-db>`, chỉ máy trong tailnet và được Tailscale ACL cho phép mới kết nối được cổng này.


## 7. Kiểm tra nhanh

### Từ máy client: gọi được API, không vào được DB

```bash
BACKEND_IP=<tailscale-ip-backend>
DB_IP=<tailscale-ip-db>

# Gọi API, mong đợi 200
curl -s -o /dev/null -w "%{http_code}" \
  http://${BACKEND_IP}:8080/api/v1/sections
# Kết quả: 200

# Kết nối trực tiếp DB, mong đợi thất bại
nc -zv -w 3 ${DB_IP} 5432
# Kết quả: Connection refused hoặc timeout
```

### Từ máy-backend: kết nối được DB

```bash
nc -zv <tailscale-ip-db> 5432
curl http://<tailscale-ip-backend>:8080/api/v1/health
```

## 8. Tailscale ACL mẫu

Thêm policy sau vào Tailscale Admin Console, mục Access Controls, để chặn máy client kết nối cổng 5432:

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

Sau khi áp dụng ACL, chạy lại [mục 7](#7-kiểm-tra-nhanh) để kiểm tra.


## 9. Thứ tự triển khai

```
1. Tailscale: 4 máy join tailnet, gán tag
2. Áp dụng Tailscale ACL
3. máy-db: git clone, tạo .env, docker compose up -d postgres
4. máy-backend: git clone, đặt biến môi trường, ./mvnw spring-boot:run
5. Kiểm tra health từ máy-backend
6. máy-client-1 và máy-client-2: git clone, cài k6, chạy full_scenario.js
```
