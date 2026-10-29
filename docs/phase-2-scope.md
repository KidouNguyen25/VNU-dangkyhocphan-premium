# Bài toán pha 2

## Đề bài

Theo [official-requirements.md](official-requirements.md), pha 2 lấy hệ thống pha 1 làm nền để cải tiến các thuộc tính chất lượng. Nhóm phải đề xuất kiến trúc, đo trước và sau khi cải tiến, giải thích lý do từng thay đổi. Mọi lần đo chạy trên cùng một cấu hình phần cứng, nhóm chọn Kaggle CPU.

## Bài toán cụ thể

Khi mở đăng ký, hàng nghìn sinh viên truy cập cùng lúc và cùng đăng ký các lớp còn chỗ. Hệ thống đặt mục tiêu 10.000 người dùng đồng thời (D6). Pha 1 mới đo tới 1000 VU, pha 2 nhắm gấp 10 lần.

Yêu cầu: ở mức tải gấp 10 lần, độ trễ và thông lượng phải ở mức chấp nhận được, đồng thời dữ liệu vẫn đúng như pha 1: không vượt sĩ số, không đăng ký trùng lớp, trùng môn, trùng ca, không vượt tín chỉ, gửi lại cùng `Idempotency-Key` thì nhận cùng phản hồi, hủy chỉ giảm `enrolled_count` một lần. Sau mỗi lần đo, bảy bất biến trong `docker/verify/invariants.sql` phải PASS.

## Kết quả pha 1

Xem tại [load-test-report.md](load-test-report.md). Cả hai cấu hình đều chạy trên một máy.

| | Kaggle (4 vCPU) | Laptop Ryzen 5 5625U |
| --- | --- | --- |
| Mức tải đạt ngưỡng tạm | 100 VU | 300 VU |
| Thông lượng tối đa | 130–140 req/s | 270–285 req/s |
| p95 ở 1000 VU | 13 418 ms | 8 258 ms |
| p95 đăng nhập ở 1000 VU | 16 227 ms | 11 575 ms |
| Lỗi ở 1000 VU | 0,03 % | 0 % |

Ngưỡng tạm lấy theo `load/k6/full_scenario.js` (p95 < 3 s, p99 < 5 s).

Nhận xét:

- Dữ liệu đúng ở mọi mức tải. Đây là điều nhóm ưu tiên từ đầu và pha 2 phải giữ được.
- Thông lượng dừng ở khoảng 140 req/s. Tăng VU chỉ làm hàng đợi dài thêm: p95 trên Kaggle tăng từ 137 ms (50 VU) lên 7 775 ms (500 VU).
- Đăng nhập chậm hơn đăng ký nhiều. Nguyên nhân có thể là bcrypt chiếm CPU, nhóm đề xuất kiểm chứng và tối ưu tại pha 2.
- Máy mạnh hơn chỉ nâng mức tải chịu được; trên laptop, 1000 VU chưa đạt.
- Kịch bản k6 cho mỗi VU đăng nhập lặp lại, trong khi thực tế sinh viên đăng nhập một lần và dùng JWT trong 60 phút. Vì vậy kịch bản có vẻ như đang nặng hơn thực tế.

## Thuộc tính cần cải tiến

Chỉ số chính là p95 và thông lượng, vì pha 1 đã có số đo để so sánh. Ngoài ra:

- Khả năng mở rộng: hiện có một backend và một PostgreSQL, CPU là điểm nghẽn.
- Hành vi khi quá tải: hệ thống chậm dần thay vì từ chối sớm; 4 lỗi 5xx ở 1000 VU chưa rõ nguyên nhân.
- Khả năng quan sát: chưa đo CPU riêng của DB, cache hit ratio và độ trễ Tailscale.

## Hướng cải tiến dự kiến

Các hướng dưới đây chưa được chốt. Hướng nào không cải thiện số đo sẽ loại bỏ sau.

1. Sửa kịch bản k6 để mỗi VU đăng nhập một lần; đo riêng luồng đăng nhập. Không ảnh hưởng tính đúng.
2. Xem lại cost factor bcrypt hoặc tách đăng nhập khỏi đường đăng ký. Giảm cost làm mật khẩu kém an toàn hơn, cần cân nhắc.
3. Chỉnh pool kết nối, index, truy vấn kiểm ràng buộc để giữ khóa `account` và `section` ngắn hơn. An toàn nếu giữ đúng thứ tự khóa.
4. Cache cho `GET /sections` và `GET /registration`. Số chỗ trống trong cache chỉ để hiển thị, không dùng để quyết định đăng ký.
5. Chạy nhiều backend sau load balancer. Rate limit hiện lưu trong bộ nhớ ứng dụng nên cần sửa.
6. Đưa đăng ký vào hàng đợi và trả 202. Hướng này thay đổi API pha 1, cần thêm trạng thái chờ, endpoint tra cứu và kiểm thử mới (D3, D4), nên chỉ xét sau các hướng trên.

## Phương pháp đo

- Cùng Kaggle CPU, cùng kịch bản k6, cùng dữ liệu mẫu, cùng các mức VU. Ghi commit của mỗi lần đo.
- Mỗi mức chạy nhiều lần. Ở pha 1, ba lần chạy 1000 VU cho p95 lệch nhau tới 50 % (12 479, 18 668, 13 418 ms).
- Đo từng cải tiến riêng rồi đo gộp.
- Trước khi lấy mốc, sửa lỗi đếm `error_rate` trong kịch bản và chạy lại pha 1 trên cấu hình pha 2. Không dùng lại số liệu cũ.
- Báo cáo gồm VU, tổng request, req/s, p50/p95/p99, tỷ lệ lỗi, CPU, RAM, tỷ lệ phát lại idempotency, kết quả bất biến.

Pha 2 hoàn thành khi: bất biến PASS ở mọi mức; TC01–TC14 vẫn chạy được; p95 hoặc thông lượng cải thiện vượt mức sai lệch giữa các lần chạy; mỗi cải tiến nêu rõ điểm nghẽn, giải pháp và đánh đổi. D6 phải được chốt trước lần đo cuối.

Ngoài phạm vi: giao diện riêng, triển khai ra Internet, đổi nghiệp vụ pha 1 (trừ khi bắt buộc và đã ghi vào [open-decisions.md](open-decisions.md)).

## Việc còn lại từ pha 1

Các việc sau là tính năng, không phải thuộc tính chất lượng. Nhóm sẽ quyết định có đưa vào pha 2 hay không.

- Đổi lịch học từ ca sang tiết, 12 tiết/ngày (D16).
- Xử lý dòng `FAILED` trong `enrollment` (D12).
- Kiểm tra môn tiên quyết, cần bảng `student_course_result`.
- Giờ học, phòng học, cần bảng `section_schedule`.
- Ngưỡng đạt và thời lượng phát tải (D6).
- Tìm nguyên nhân 4 lỗi 5xx ở 1000 VU (`load-test-report.md` mục 9).
