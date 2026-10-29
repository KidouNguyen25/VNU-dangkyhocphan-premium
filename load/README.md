# Thử tải bằng k6

Thư mục chứa kịch bản k6, script kiểm thử đồng thời và script kiểm tra bất biến dữ liệu sau tải.

| Đường dẫn | Nội dung |
| --- | --- |
| `k6/login.js` | Chỉ đo đăng nhập |
| `k6/full_scenario.js` | Luồng đầy đủ: đăng nhập, xem đợt, xem lớp, đăng ký, hủy |
| `k6/accounts.json` | Danh sách tài khoản sinh viên dùng khi phát tải |
| `scripts/concurrent_test.sh` | Nhiều request cùng đăng ký một lớp |
| `scripts/run_invariants.sh` | Chạy `docker/verify/invariants.sql` |
| `notebooks/` | Notebook chạy toàn bộ trên Kaggle hoặc Colab |

Nhóm dùng k6 vì kịch bản viết bằng JavaScript, máy phát tải tốn ít tài nguyên hơn JMeter và có sẵn ramping VU.

## Chuẩn bị

Cài k6 trên Windows:

```powershell
winget install k6 --source winget
```

Trên Ubuntu/Debian:

```bash
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6
```

Nạp dữ liệu mẫu trên máy chạy PostgreSQL:

```bash
docker compose up -d postgres
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v pw='password123' -v students=1000 -v period_status=OPEN -f - < docker/seed/seed_dev.sql
```

Mật khẩu `password123` chỉ dùng cho môi trường thử; `STUDENT_PASSWORD` ở các lệnh dưới phải trùng với giá trị này.

## Chạy

Đo riêng đăng nhập (bcrypt và phát JWT):

```bash
k6 run -e BASE_URL=http://localhost:8080/api/v1 -e STUDENT_PASSWORD=password123 \
  -e VUS=100 -e TOTAL_REQUESTS=1000 load/k6/login.js
```

Luồng đầy đủ với 1000 VU (tăng trong 30 giây, giữ 60 giây, giảm trong 30 giây):

```bash
k6 run -e BASE_URL=http://localhost:8080/api/v1 -e STUDENT_PASSWORD=password123 -e VUS=1000 \
  --out json=load/results/result_$(date +%Y%m%d_%H%M%S).json \
  load/k6/full_scenario.js
```

Chia tải cho hai máy client qua Tailscale, mỗi máy 500 tài khoản. Máy 1 dùng SV00001 đến SV00500:

```bash
k6 run -e BASE_URL=http://<tailscale-ip-backend>:8080/api/v1 -e STUDENT_PASSWORD=password123 \
  -e VUS=500 -e ACCOUNT_OFFSET=0 -e ACCOUNT_LIMIT=500 load/k6/full_scenario.js
```

Máy 2 dùng SV00501 đến SV01000:

```bash
k6 run -e BASE_URL=http://<tailscale-ip-backend>:8080/api/v1 -e STUDENT_PASSWORD=password123 \
  -e VUS=500 -e ACCOUNT_OFFSET=500 -e ACCOUNT_LIMIT=500 load/k6/full_scenario.js
```

## Kiểm tra bất biến sau tải

```bash
bash load/scripts/run_invariants.sh
```

Mọi truy vấn trong `invariants.sql` phải trả 0 dòng.
