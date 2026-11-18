# Thử tải bằng k6

Thư mục chứa kịch bản k6, script kiểm thử đồng thời và script kiểm tra bất biến dữ liệu sau tải.

| Đường dẫn | Nội dung |
| --- | --- |
| `k6/login.js` | Chỉ đo đăng nhập |
| `k6/full_scenario.js` | Luồng đầy đủ: đăng nhập, xem đợt, xem lớp, đăng ký, gửi lại, hủy |
| `k6/auth.js` | `login()` và `send()` dùng chung cho hai kịch bản: gặp 429 thì chờ `Retry-After` rồi thử lại (tối đa 5 lần), đếm `rejected` theo nhóm |
| `k6/accounts.json` | Danh sách tài khoản sinh viên cho `login.js` |
| `scripts/concurrent_test.sh` | Nhiều request cùng đăng ký một lớp |
| `scripts/run_invariants.sh` | Chạy `docker/verify/invariants.sql` |
| `notebooks/` | Notebook chạy toàn bộ trên Kaggle |

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

Cỡ seed: `full_scenario.js` dùng một tài khoản cho mỗi VU, nên `students` phải ít nhất bằng `ACCOUNT_OFFSET + VUS`. `login.js` dùng các tài khoản trong `k6/accounts.json` (SV00001 đến SV01000).

### Giới hạn tần suất khi chạy với Docker Compose

Mọi request k6 đến từ một IP, nên giới hạn mặc định (mỗi phút 600 request cho một IP, 120 cho một tài khoản, 10 lượt đăng nhập cho một mã) chặn tải ngay từ khoảng 8 VU, còn `login.js` bị chặn ngay vì mỗi VU đăng nhập lại mỗi giây. Trước khi phát tải, đặt trong `.env` rồi chạy `docker compose up -d`:

```bash
RATE_LIMIT_IP=100000
RATE_LIMIT_ACCOUNT=100000
RATE_LIMIT_LOGIN=100000
```

## Chạy

Đo riêng đăng nhập (bcrypt và phát JWT):

```bash
k6 run -e BASE_URL=http://localhost:8080/api/v1 -e STUDENT_PASSWORD=password123 \
  -e VUS=100 load/k6/login.js
```

Biến môi trường của `full_scenario.js`:

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `VUS` | `1000` | Số VU |
| `ACCOUNT_OFFSET` | `0` | VU thứ n dùng tài khoản `SV` + (`ACCOUNT_OFFSET` + n), đệm đủ 5 chữ số |
| `LOGIN_WINDOW_S` | `30` | Cửa sổ đăng nhập: VU tăng dần trong khoảng này, mỗi VU đăng nhập một lần rồi chờ hết cửa sổ để mọi VU cùng bắt đầu đăng ký |
| `SECTION_COUNT` | `36` | Khi không lớp nào còn chỗ, chọn ngẫu nhiên một lớp từ 1 đến `SECTION_COUNT` |

Luồng đầy đủ với 1000 VU (cửa sổ đăng nhập 30 giây, giữ 60 giây, giảm trong 30 giây):

```bash
k6 run -e BASE_URL=http://localhost:8080/api/v1 -e STUDENT_PASSWORD=password123 -e VUS=1000 \
  --out json=load/results/result_$(date +%Y%m%d_%H%M%S).json \
  load/k6/full_scenario.js
```

Chia tải cho hai máy client qua Tailscale, mỗi máy 500 tài khoản. Máy 1 dùng SV00001 đến SV00500:

```bash
k6 run -e BASE_URL=http://<tailscale-ip-backend>:8080/api/v1 -e STUDENT_PASSWORD=password123 \
  -e VUS=500 -e ACCOUNT_OFFSET=0 load/k6/full_scenario.js
```

Máy 2 dùng SV00501 đến SV01000:

```bash
k6 run -e BASE_URL=http://<tailscale-ip-backend>:8080/api/v1 -e STUDENT_PASSWORD=password123 \
  -e VUS=500 -e ACCOUNT_OFFSET=500 load/k6/full_scenario.js
```

## Kiểm tra bất biến sau tải

```bash
bash load/scripts/run_invariants.sh
```

Mọi truy vấn trong `invariants.sql` phải trả 0 dòng.
