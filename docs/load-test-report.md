# Báo cáo Thử tải (Load Test Report)

> **Template — điền vào sau khi chạy thực tế.**
> Mọi vị trí có `[...]` hoặc `[YYYY-MM-DD]` là placeholder cần thay bằng số liệu thật.

---

## 1. Tổng quan

| Trường | Giá trị |
|--------|---------|
| Ngày chạy | `[YYYY-MM-DD]` |
| Giờ bắt đầu | `[HH:MM UTC+7]` |
| Giờ kết thúc | `[HH:MM UTC+7]` |
| Phiên bản code (git hash) | `[git rev-parse --short HEAD]` |
| Nhánh | `[tên nhánh]` |
| Môi trường | Tailscale private network — 4 máy |
| Người thực hiện | `[tên]` |

---

## 2. Cấu hình máy

| Máy | CPU | RAM | OS | Ghi chú |
|-----|-----|-----|----|---------|
| máy-backend | `[vd: 4 vCPU Intel Xeon]` | `[vd: 8 GB]` | `[vd: Ubuntu 22.04]` | Chạy Spring Boot + Docker |
| máy-db | `[CPU]` | `[RAM]` | `[OS]` | Chạy PostgreSQL 16 + Docker |
| máy-client-1 | `[CPU]` | `[RAM]` | `[OS]` | k6, SV00001–SV00500 |
| máy-client-2 | `[CPU]` | `[RAM]` | `[OS]` | k6, SV00501–SV01000 |

---

## 3. Tham số thử tải

| Tham số | Giá trị |
|---------|---------|
| Công cụ | k6 (phiên bản `[k6 version]`) |
| Tổng người dùng ảo (VU) | 1000 (500 mỗi máy client) |
| Phân bổ tài khoản | client-1: SV00001–SV00500 · client-2: SV00501–SV01000 |
| Tổng yêu cầu mục tiêu | 5 000 |
| Script | `load/k6/full_scenario.js` |
| BASE_URL | `http://<tailscale-ip-backend>:8080/api/v1` |

### Chiến lược tải (Ramping VUs)

```
Giai đoạn       Thời gian   VU
──────────────────────────────
Ramp-up         30 s        0 → 1000
Steady state    60 s        1000 (giữ nguyên)
Ramp-down       30 s        1000 → 0
──────────────────────────────
Tổng            120 s
```

---

## 4. Kết quả đo

### 4.1 Thông lượng và độ trễ

| Chỉ số | Giá trị |
|--------|---------|
| Thông lượng (req/s) — trung bình | `[...] req/s` |
| Thông lượng (req/s) — peak | `[...] req/s` |
| p50 latency | `[...] ms` |
| p95 latency | `[...] ms` |
| p99 latency | `[...] ms` |
| Latency tối đa (max) | `[...] ms` |
| Tỷ lệ lỗi HTTP (non-2xx) | `[...]%` |
| Tổng request đã gửi | `[...]` |
| Tổng request thành công | `[...]` |

### 4.2 Tài nguyên máy-backend (lúc peak)

| Tài nguyên | Giá trị |
|-----------|---------|
| CPU | `[...]%` |
| RAM sử dụng | `[...] MB` |
| Kết nối DB pool đang dùng | `[...] / 20` |
| JVM Heap Used | `[...] MB` |

### 4.3 Tài nguyên máy-db (lúc peak)

| Tài nguyên | Giá trị |
|-----------|---------|
| CPU | `[...]%` |
| RAM sử dụng | `[...] MB` |
| Active connections | `[...]` |
| Cache hit ratio | `[...]%` |

### 4.4 Mạng Tailscale

| Chỉ số | Giá trị |
|--------|---------|
| Độ trễ Tailscale client→backend (ping) | `[...] ms` |
| Độ trễ Tailscale backend→db (ping) | `[...] ms` |
| Packet loss | `[...]%` |

### 4.5 Tỷ lệ phát lại idempotency

| Chỉ số | Giá trị |
|--------|---------|
| Tổng request POST /enrollments | `[...]` |
| Trong đó có header Idempotency-Key | `[...]` |
| Idempotency replay (X-Idempotent-Replay: true) | `[...]` |
| Tỷ lệ replay | `[...]%` |

---

## 5. Kết quả đo riêng bước đăng nhập

> Đăng nhập (bcrypt) nặng hơn các endpoint thông thường do cost factor của hash.

| Chỉ số | Giá trị |
|--------|---------|
| Tổng request POST /auth/login | `[...]` |
| p50 login latency | `[...] ms` |
| p95 login latency | `[...] ms` |
| p99 login latency | `[...] ms` |
| Tỷ lệ lỗi login (sai mật khẩu / tài khoản không tồn tại) | `[...]%` |

---

## 6. Trạng thái dữ liệu sau tải

Sau khi kết thúc thử tải, chạy script kiểm tra bất biến:

```bash
docker compose exec -T postgres \
  psql -U dkhp_app -d dkhp -f - < docker/seed/invariants.sql
```

| Kiểm tra | Kết quả |
|---------|---------|
| Kết quả `invariants.sql` | `[PASS / FAIL]` |
| Output đầy đủ | Xem [Phụ lục A](#phụ-lục-a-output-invariantssql) |
| Tổng bản ghi enrollment sau tải | `[...]` |
| Bản ghi ở trạng thái PROCESSING mồ côi | `[YES — có vấn đề / NO — sạch]` |
| Tổng tài khoản đã đăng ký ít nhất 1 lớp | `[...]` |
| Section bị over-enroll (vượt capacity) | `[YES — có vấn đề / NO — không có]` |

---

## 7. Ngưỡng đạt (Thresholds)

> **Ngưỡng chưa được chốt — đang chờ xác nhận từ trưởng nhóm (D6).**

| Chỉ số | Ngưỡng mục tiêu | Kết quả | Đạt? |
|--------|----------------|---------|------|
| p95 latency toàn hệ thống | `[chưa xác định]` | `[...] ms` | `[chưa đánh giá]` |
| p95 latency login | `[chưa xác định]` | `[...] ms` | `[chưa đánh giá]` |
| Tỷ lệ lỗi | `[chưa xác định]` | `[...]%` | `[chưa đánh giá]` |
| Thông lượng tối thiểu | `[chưa xác định]` | `[...] req/s` | `[chưa đánh giá]` |
| Không over-enroll | Bắt buộc | `[PASS/FAIL]` | `[chưa đánh giá]` |
| Không PROCESSING mồ côi | Bắt buộc | `[PASS/FAIL]` | `[chưa đánh giá]` |

**Kết luận tổng thể:** `[Đang chờ ngưỡng được chốt từ D6 để kết luận PASS/FAIL chính thức.]`

---

## 8. Dữ liệu thô

| File | Đường dẫn |
|------|-----------|
| Kết quả client-1 | `load/results/result_client1_YYYYMMDD_HHMMSS.json` |
| Kết quả client-2 | `load/results/result_client2_YYYYMMDD_HHMMSS.json` |
| Log máy-backend | `[docker compose logs api > logs/backend_YYYYMMDD.log]` |
| Snapshot DB metrics | `[pg_stat_activity snapshot, nếu có]` |

Lệnh tổng hợp kết quả từ 2 file JSON:

```bash
# Xem summary từ file JSON của k6
k6 inspect load/results/result_client1_*.json
k6 inspect load/results/result_client2_*.json
```

---

## Phụ lục A: Output `invariants.sql`

```
[Dán toàn bộ output của lệnh psql invariants.sql vào đây]

Ví dụ kết quả PASS:
 check_name                        | result
-----------------------------------+--------
 no_orphan_processing              | PASS
 no_over_enrolled_section          | PASS
 enrollment_count_matches_payments | PASS
(3 rows)
```

---

## Phụ lục B: Biểu đồ độ trễ theo thời gian

> *Dán ảnh chụp màn hình k6 dashboard hoặc Grafana vào đây (nếu có).*

```
[Screenshot: VU count & request rate over time]
[Screenshot: p95/p99 latency over time]
[Screenshot: Error rate over time]
```
