# Báo cáo Thử tải (Load Test Report)

Số liệu dưới đây đo trên một máy Kaggle (backend, PostgreSQL và k6 chạy chung). Mục 6.1 đo lại cùng kịch bản trên một laptop Ryzen 5 5625U, cũng là một máy. Các mục chỉ đo được ở bản bốn máy được ghi "Chưa đo". Ngưỡng đạt chính thức (D6) chưa chốt, nên kết luận PASS/FAIL ở mục 8 chỉ là tạm thời, theo ngưỡng trong `load/k6/full_scenario.js`.

## 1. Tổng quan

| Trường | Giá trị |
|--------|---------|
| Ngày chạy | 29/10/2026 |
| Giờ bắt đầu lần chạy 1000 VU | 02:32:40 (UTC+7), tức 19:32:40 UTC ngày 28/10 |
| Giờ kết thúc lần chạy 1000 VU | 02:34:43 (UTC+7) |
| Thời gian chạy cả notebook | 1507 giây (1000 VU, 10 mức quét, bất biến, biểu đồ) |
| Phiên bản code (git hash) | `8ae5d7e` |
| Nhánh | `main` (`origin/main`, clone lúc chạy) |
| Môi trường | Một container Kaggle, không qua Tailscale |
| Công cụ chạy | `load/notebooks/loadtest_kaggle.ipynb` |
| Bản chạy trên Kaggle | notebook `nguyentoan25/qc-dangkyhocphan-vnu-premium`, Version 5 |

## 2. Cấu hình máy

Backend, PostgreSQL và k6 cùng chạy trong một container, tranh nhau CPU.

| Thành phần | Giá trị |
|-----------|---------|
| CPU | 4 vCPU |
| RAM | 31,3 GB |
| HĐH | Ubuntu 24.04, kernel Linux 6.18 |
| Java | OpenJDK 21.0.12 (chạy với `-Xmx2g`) |
| PostgreSQL | 16.15 |
| k6 | v0.54.0 |
| Spring Boot | jar `course-registration-0.1.0-SNAPSHOT.jar` (33,0 MB) |

## 3. Tham số thử tải

| Tham số | Giá trị |
|---------|---------|
| Công cụ | k6 v0.54.0 |
| Script | `load/k6/full_scenario.js` |
| Kịch bản mỗi VU | đăng nhập, xem đợt, xem lớp, đăng ký (có `Idempotency-Key`), hủy |
| Tài khoản | `SV00001` đến `SV01000` (1000 tài khoản, `seed_dev.sql`) |
| BASE_URL | `http://127.0.0.1:8080/api/v1` |
| Giới hạn tốc độ | Nâng gần như vô hạn (`RATE_LIMIT_IP`, `RATE_LIMIT_ACCOUNT`, `RATE_LIMIT_LOGIN` = 100000000) để đo ứng dụng, không đo bộ chặn 429 |
| Lần chạy chính | 1000 VU |
| Quét ngưỡng | 50, 100, 150, 200, 250, 300, 350, 400, 450, 500 VU, mỗi mức một lần |

Số yêu cầu không cố định mà do kịch bản và độ trễ quyết định. Lần chạy 1000 VU gửi 13 863 yêu cầu, không phải 5 000 như mục tiêu ban đầu.

### Chiến lược tải (Ramping VUs, mỗi lần chạy)

```
Giai đoạn       Thời gian   VU
──────────────────────────────
Ramp-up         30 s        0 → N
Steady state    60 s        N (giữ nguyên)
Ramp-down       30 s        N → 0
──────────────────────────────
Tổng            120 s
```

Trước mỗi mức quét, notebook xóa `enrollment`, `idempotency_record` và đặt `enrolled_count = 0`, nên các mức độc lập nhau.

## 4. Kết quả đo lần chạy 1000 VU

### 4.1 Thông lượng và độ trễ

| Chỉ số | Giá trị |
|--------|---------|
| Thông lượng trung bình | 114,6 req/s |
| Thông lượng đỉnh (cửa sổ 1 giây) | 355 req/s |
| p50 latency | 6 002,7 ms |
| p95 latency | 13 417,5 ms |
| p99 latency | 16 289,1 ms |
| Latency tối đa | 21 807,0 ms |
| Tỷ lệ lỗi (không tính 409, 422) | 0,03 % |
| Tổng request đã gửi | 13 863 |
| Tổng request thành công (2xx) | 13 859 |

Mã trạng thái: 200 × 8 558, 201 × 2 734, 204 × 2 567, 500 × 1, 503 × 3.

### 4.2 Tài nguyên (lúc peak, cùng một máy)

| Tài nguyên | Giá trị |
|-----------|---------|
| CPU Java, đỉnh | 384,7 % (tối đa 400 % với 4 vCPU) |
| CPU Java, trung bình | 347,1 % |
| CPU toàn máy, trung bình | 98,0 % (đỉnh 100,0 %) |
| RAM Java (RSS) đỉnh | 552,4 MB |
| Kết nối PostgreSQL đỉnh | 21 |
| JVM Heap Used | Chưa đo (chỉ có RSS) |

Số kết nối 21 gồm cả kết nối của bộ lấy mẫu trong notebook.

### 4.3 PostgreSQL

| Tài nguyên | Giá trị |
|-----------|---------|
| RAM (RSS) đỉnh của các tiến trình `postgres` | 721,4 MB |
| Kết nối đang hoạt động, đỉnh | 21 |
| CPU riêng của DB | Chưa đo (chung máy) |
| Cache hit ratio | Chưa đo |

### 4.4 Mạng Tailscale

Chưa đo. Bản Kaggle chạy qua `127.0.0.1`, không có độ trễ mạng.

### 4.5 Tỷ lệ phát lại idempotency

| Chỉ số | Giá trị |
|--------|---------|
| Tổng request `POST /enrollments` | 2 734 |
| Có header `Idempotency-Key` | 2 734 (kịch bản luôn gửi) |
| Phát lại (`X-Idempotent-Replay: true`) | 0 |
| Tỷ lệ phát lại | 0 % |
| Số dòng `idempotency_record` sau tải | 2 807 |

Kịch bản k6 tạo khóa mới cho mỗi request nên đường phát lại chưa được thử. Đây là giới hạn của kịch bản, không phải bằng chứng đường phát lại hoạt động tốt.

## 5. Kết quả đo riêng bước đăng nhập

Đăng nhập (bcrypt) nặng hơn các endpoint thông thường do cost factor của hash.

| Chỉ số | Giá trị |
|--------|---------|
| Tổng request `POST /auth/login` | 2 907 |
| p50 login latency | 10 633,3 ms |
| p95 login latency | 16 227,1 ms |
| p99 login latency | 17 599,5 ms |
| Tỷ lệ login không trả 200 | 0,03 % |

p50 của đăng nhập (10,6 s) cao hơn p50 toàn hệ thống (6,0 s) gần gấp đôi.

## 6. Kết quả quét theo ngưỡng 50 đến 500 VU

Mỗi mức chạy 120 giây. "Đạt QC" theo ngưỡng của script: p95 < 3 000 ms, p99 < 5 000 ms, bất biến PASS.

| VU | p95 (ms) | p99 (ms) | Thông lượng (req/s) | Bất biến | Đạt QC |
|---:|---------:|---------:|--------------------:|:--------:|:------:|
| 50 | 137 | 228 | 86,6 | PASS | ĐẠT |
| 100 | 1 795 | 2 331 | 115,3 | PASS | ĐẠT |
| 150 | 3 194 | 4 020 | 124,2 | PASS | KHÔNG ĐẠT (p95) |
| 200 | 4 120 | 5 238 | 130,9 | PASS | KHÔNG ĐẠT |
| 250 | 5 363 | 6 928 | 136,7 | PASS | KHÔNG ĐẠT |
| 300 | 5 853 | 7 752 | 130,3 | PASS | KHÔNG ĐẠT |
| 350 | 6 391 | 8 365 | 138,3 | PASS | KHÔNG ĐẠT |
| 400 | 6 707 | 8 681 | 140,3 | PASS | KHÔNG ĐẠT |
| 450 | 7 216 | 9 290 | 139,8 | PASS | KHÔNG ĐẠT |
| 500 | 7 775 | 9 744 | 137,0 | PASS | KHÔNG ĐẠT |

Mức tải chịu được trên máy này là 100 VU (mức cao nhất mà mọi mức từ 50 trở lên đều đạt).

Bảng đầy đủ hơn (p50, max, p95 riêng của đăng nhập và đăng ký, CPU từng mức) nằm trong `sweep.csv` của gói `load_results.zip` ở tab Output của notebook. Gói này chưa được tải về `load/results/`.

Cột tỷ lệ lỗi của lần quét này không dùng được. Kịch bản k6 chỉ ghi giá trị vào `error_rate` khi có lỗi, nên metric luôn báo 100 % nếu có ít nhất một lỗi, kể cả khi chỉ có vài yêu cầu. Cột này hiện 0 % từ 50 đến 300 VU và 100 % từ 350 VU, nghĩa là từ 350 VU có ít nhất một lỗi, không phải mọi yêu cầu đều lỗi. Notebook đã sửa (số lần lỗi chia tổng yêu cầu) nhưng chưa chạy lại, nên lần quét cần chạy lại để có tỷ lệ lỗi đúng.

### 6.1 Đo lại trên máy cá nhân

Chạy lại cùng kịch bản, cùng commit `8ae5d7e`, ngày 29/10/2026 từ 12:50 đến 13:13 (UTC+7), trên laptop của nhóm. Vẫn là một máy: backend, PostgreSQL và k6 chạy chung, mỗi thành phần trong một container Docker.

| Thành phần | Giá trị |
|-----------|---------|
| CPU | AMD Ryzen 5 5625U, 6 nhân / 12 luồng (chip laptop dòng U) |
| RAM | 15,4 GB |
| Môi trường | Windows 11, Docker Desktop (WSL2) |
| Backend | jar build từ `8ae5d7e`, chạy trong `eclipse-temurin:21-jre` với `-Xmx2g` |
| PostgreSQL | `postgres:16`, schema `docker/initdb/*`, `seed_dev.sql` với 1000 sinh viên |
| k6 | `grafana/k6:0.54.0`, `load/k6/full_scenario.js` |
| Giới hạn tốc độ | Nâng gần như vô hạn, giống bản Kaggle |

Tỷ lệ lỗi ở bảng này tính đúng (số lần lỗi chia tổng yêu cầu), không bị lỗi đếm như lần quét Kaggle.

| VU | Yêu cầu | req/s | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) | Login p95 (ms) | Đăng ký p95 (ms) | Lỗi | Bất biến | Đạt QC | p95 Kaggle (ms) |
|---:|--------:|------:|---------:|---------:|---------:|---------:|---------------:|-----------------:|----:|:--------:|:------:|----------------:|
| 50 | 10 450 | 86,4 | 7 | 147 | 222 | 459 | 223 | 23 | 0 | PASS | ĐẠT | 137 |
| 100 | 19 770 | 161,9 | 7 | 272 | 699 | 1 731 | 699 | 74 | 0 | PASS | ĐẠT | 1 795 |
| 150 | 26 725 | 220,3 | 11 | 665 | 1 412 | 2 606 | 1 412 | 73 | 0 | PASS | ĐẠT | 3 194 |
| 200 | 24 675 | 202,3 | 46 | 2 186 | 3 337 | 5 366 | 3 338 | 348 | 0 | PASS | ĐẠT | 4 120 |
| 250 | 27 625 | 227,5 | 47 | 2 853 | 4 322 | 7 759 | 4 321 | 410 | 0 | PASS | ĐẠT | 5 363 |
| 300 | 32 740 | 268,5 | 57 | 2 837 | 4 152 | 6 120 | 4 153 | 309 | 0 | PASS | ĐẠT | 5 853 |
| 350 | 34 485 | 283,2 | 92 | 3 136 | 4 081 | 5 061 | 4 081 | 626 | 0 | PASS | KHÔNG ĐẠT (p95) | 6 391 |
| 400 | 33 375 | 275,0 | 191 | 3 703 | 4 871 | 6 645 | 4 871 | 729 | 0 | PASS | KHÔNG ĐẠT (p95) | 6 707 |
| 450 | 31 385 | 257,7 | 330 | 4 286 | 6 084 | 9 623 | 6 084 | 1 241 | 0 | PASS | KHÔNG ĐẠT | 7 216 |
| 500 | 33 835 | 278,1 | 298 | 4 562 | 5 942 | 7 706 | 5 942 | 1 328 | 0 | PASS | KHÔNG ĐẠT | 7 775 |
| 1000 | 30 536 | 250,1 | 1 895 | 8 258 | 11 588 | 16 166 | 11 575 | 4 603 | 0 | PASS | KHÔNG ĐẠT | 13 418 |

Mức tải chịu được trên máy này là 300 VU, so với 100 VU trên Kaggle.

- Thông lượng chạm trần khoảng 270 đến 285 req/s, gấp đôi Kaggle (khoảng 140 req/s).
- Từ 100 VU trở lên, p95 thấp hơn Kaggle khoảng 1,6 đến 6,5 lần.
- Không có yêu cầu lỗi nào ở cả 11 lần chạy. Bảy bất biến PASS ở mọi mức.
- p95 của đăng nhập gần bằng p99 toàn hệ thống ở mọi mức, còn p95 của đăng ký thấp hơn nhiều (4,6 s so với 11,6 s ở 1000 VU). Số này củng cố nhận xét đăng nhập là phần nặng nhất.
- 300 VU nhỉnh hơn 250 VU một chút. Laptop tăng xung không đều giữa các lượt, và mỗi mức chỉ chạy một lần, nên đọc số từng mức với sai số đáng kể.

## 7. Trạng thái dữ liệu sau tải

Sau lần chạy 1000 VU, chạy `docker/verify/invariants.sql` (hoặc `bash load/scripts/run_invariants.sh`):

| Kiểm tra | Kết quả |
|---------|---------|
| Kết quả `invariants.sql` (a đến g) | PASS, cả bảy truy vấn trả 0 dòng |
| Output đầy đủ | Xem [Phụ lục A](#phụ-lục-a-output-invariantssql) |
| Tổng bản ghi enrollment sau tải | 103 (cả 103 ở trạng thái `ENROLLED`) |
| Bản ghi idempotency `PROCESSING` mồ côi | NO, sạch (0) |
| Tổng tài khoản đã đăng ký ít nhất 1 lớp | 103 |
| Lớp bị over-enroll (vượt capacity) | NO, không có |

Bất biến cũng PASS ở cả 10 mức quét (cột Bất biến, mục 6).

## 8. Ngưỡng đạt (Thresholds)

| Chỉ số | Ngưỡng tạm | Kết quả (1000 VU) | Đạt? |
|--------|-----------|-------------------|------|
| p95 latency toàn hệ thống | < 3 000 ms | 13 417,5 ms | Không |
| p99 latency toàn hệ thống | < 5 000 ms | 16 289,1 ms | Không |
| p95 latency login | Chưa xác định | 16 227,1 ms | Chưa đánh giá |
| Tỷ lệ lỗi | < 10 % | 0,03 % | Có |
| Thông lượng tối thiểu | Chưa xác định | 114,6 req/s | Chưa đánh giá |
| Không over-enroll | Bắt buộc | PASS | Có |
| Không PROCESSING mồ côi | Bắt buộc | PASS | Có |

Kết luận tạm thời: hệ thống đúng về dữ liệu ở mọi mức tải đã thử, nhưng không đạt ngưỡng độ trễ tạm ở 1000 VU (và từ 150 VU trở lên) trên máy 4 vCPU dùng chung.

## 9. Nhận xét

1. Hiện tại giới hạn chính nằm ở CPU, và phần lớn là đăng nhập. Ở 1000 VU, CPU toàn máy trung bình 98 %, Java dùng trung bình 347 % trên 4 vCPU. Đăng nhập có p50 10,6 s so với 6,0 s của toàn hệ thống. Cách giải thích hợp lý nhất là băm bcrypt tranh CPU với mọi việc khác. Đây là suy luận từ số đo, chưa được kiểm chứng bằng cách đổi cost factor hoặc đo riêng `login.js` ở tải thấp.
2. Thông lượng chạm trần quanh 130 đến 140 req/s từ 200 VU. Tăng VU từ 200 lên 500 không tăng thông lượng, chỉ kéo dài hàng đợi. p95 tăng từ 137 ms (50 VU) lên 7 775 ms (500 VU). Đây là dạng quá tải điển hình khi thêm người dùng vào hệ thống đã bão hòa.
3. Điểm gãy nằm giữa 100 và 150 VU: p95 ở 100 VU là 1 795 ms, ở 150 VU là 3 194 ms, vượt ngưỡng tạm 3 000 ms. p99 còn đạt ở 150 VU (4 020 ms) và vượt từ 200 VU (5 238 ms).
4. Dữ liệu luôn đúng. Cả 11 lần chạy (10 mức quét và lần 1000 VU) đều PASS bảy bất biến. Không có over-enroll, không có bản ghi `PROCESSING` mồ côi, kể cả khi hệ thống quá tải. Với hệ thống đăng ký học phần, đây là kết quả quan trọng nhất.
5. Lỗi rất ít. Ở 1000 VU chỉ có 4 yêu cầu 5xx (một 500, ba 503) trên 13 863, tức 0,03 %. Chưa điều tra nguyên nhân các lỗi này. Hệ thống chậm đi thay vì trả lỗi hàng loạt khi quá tải.
6. Ba lần chạy 1000 VU trên Kaggle cho p95 lần lượt 12 479 ms (commit `b3438c5`, 15 763 yêu cầu), 18 668 ms (`3131630`, 11 956 yêu cầu) và 13 418 ms (`8ae5d7e`, 13 863 yêu cầu). Chênh lệch này lớn hơn mọi khác biệt do code, nên không so sánh hiệu năng giữa các commit bằng các số này. Dùng chúng để biết bậc độ lớn, không để so từng phần trăm.
7. Điều kiện đo làm số liệu xấu đi so với triển khai thật. k6 chạy chung máy nên giành CPU của backend. Đổi lại, không có độ trễ mạng và giới hạn tốc độ đã bị tắt. Hai yếu tố này khác chiều nhau, nên không thể suy ra số bốn máy từ số này.
8. Phần cứng dời điểm gãy nhưng không đổi hình dạng kết quả. Trên laptop 6 nhân (mục 6.1), mức chịu được tăng từ 100 lên 300 VU và thông lượng trần gấp đôi, nhưng 1000 VU vẫn không đạt (p95 8,3 s, đăng nhập p95 11,6 s). Muốn đạt 1000 VU với kịch bản đăng nhập liên tục thì phải thêm CPU cho backend, hoặc đo bằng kịch bản sát hành vi thật hơn (đăng nhập một lần).

## Phụ lục A: Output `invariants.sql`

Kết quả sau lần chạy 1000 VU (commit `8ae5d7e`). Mỗi truy vấn phải trả 0 dòng:

```
[a] enrolled_count khác số dòng ENROLLED của lớp            (0 rows)
[b] enrolled_count ngoài [0, capacity]                      (0 rows)
[c] một sinh viên có hai lớp cùng course_code trong một kỳ  (0 rows)
[d] một sinh viên có hai lớp trùng bit session trong một kỳ (0 rows)
[e] tổng tín chỉ vượt credit_limit                          (0 rows)
[f] bản ghi idempotency PROCESSING quá hạn                  (0 rows)
[g] nhiều hơn một đợt OPEN                                  (0 rows)
```

Tóm tắt: `violating_rows_per_invariant` bằng 0 ở cả bảy mục, `result` = `PASS`, `enrollment_total` = 103, `idempotency_processing` = 0.

## Phụ lục B: Biểu đồ theo thời gian

Chưa chèn ảnh vào tài liệu. Các hình do notebook sinh, nằm trong `charts/` của gói kết quả:

| Hình | Nội dung |
|------|----------|
| `01-thong-luong-vu.png` | Thông lượng và số VU theo thời gian |
| `02-do-tre-theo-thoi-gian.png` | p50, p95, p99 theo cửa sổ 5 giây |
| `03-do-tre-theo-endpoint.png` | Độ trễ theo endpoint |
| `06-ty-le-loi.png` | Tỷ lệ lỗi theo thời gian |
| `07-tai-nguyen.png` | CPU và RAM |
| `09-nhiet-p95.png` | Bản đồ nhiệt p95 theo endpoint và thời gian |
| `13-nguong-vu.png` | Độ trễ, thông lượng và CPU theo số VU (quét 50 đến 500) |

Các hình còn lại: `04` phân bố độ trễ, `05` mã trạng thái, `08` phân phối tích lũy, `10` thông lượng theo endpoint, `11` kết nối PostgreSQL, `12` bảng điểm QC.
