# Yêu cầu chính thức và tiêu chí chấm

## Đề bài chính thức

### Đề bài

Đề bài: các nhóm cần thực hiện hai pha. Pha 1 bao gồm việc xây dựng hệ thống với các chức năng cơ bản theo yêu cầu. Pha 2 dựa vào dự án từ pha 1 để đưa ra các cải tiến liên quan đến các thuộc tính chất lượng.

### Yêu cầu Pha 1

Mỗi nhóm chọn một nghiệp vụ (rạp phim, giỏ hàng, bãi đỗ xe,...) và xây dựng một dịch vụ backend thỏa các yêu cầu sau.

**API:**
- Sử dụng REST API, giao tiếp qua JSON.
- Gồm tối thiểu các endpoint có method: POST, GET, DELETE.
- Có tài liệu API (OpenAPI/Swagger).

**Cấu trúc cơ bản:**
- Phân tầng rõ: API → Nghiệp vụ → Truy cập dữ liệu. Tầng nghiệp vụ không import framework web hay thư viện DB.
- Truy cập dữ liệu qua Repository/Data Access Layer phía dưới dùng ORM hoặc data mapper tùy chọn.

**Bảo mật:**
- Hỗ trợ đăng nhập.
- Ít nhất 1 endpoint GET và 1 endpoint POST cần xác thực.
- Xác thực qua middleware/filter/interceptor của framework, không viết lặp trong từng endpoint.

**Triển khai:**
- Ngôn ngữ: Không cố định.
- Có hỗ trợ đóng gói gói Docker.

**Yêu cầu chung:**
- Các nhóm có Github công khai và commit cẩn thận. File readme.md mô tả kiến trúc và đặc tả của dự án.
- Chỉ triển khai chức năng cơ bản, không cần tối ưu.
- Kiểm thử tải trên Kaggle CPU.

### Gợi ý Pha 2:

- Dựa vào đánh giá hệ thống đã có hoặc đặc tả của bài toán để đề xuất các kiến trúc phù hợp.
- Cần có đánh giá trước và sau khi cải tiến, chỉ rõ lý do tại sao có các cải tiến trên.
- Cần đánh giá duy nhất trên một cấu hình phần cứng, để cố định, nhóm nên sử dụng CPU trên Kaggle.
- Triển khai các kiến trúc phù hợp với từng bài toán.

## Rubric

**Trạng thái:** Thầy không có rubric chính thức. Thầy chỉ đề cập:

> Hoàn thiện đủ chức năng của hệ thống, readme, đặc tả API và code cẩn thận là được, không AI slop quá.
Ngoài ra đề tài thể hiện rõ các vấn đề có thể phân tích được thì sẽ thêm điểm cộng nhé.

## Yêu cầu nộp bài

| Trường | Giá trị |
| --- | --- |
| Danh sách tệp hoặc sản phẩm | Link github |
| Định dạng và quy cách đặt tên | Không rõ |
| Kênh nộp | Đoán chắc là nộp trên portal |
| Hạn nộp và múi giờ | 27 hoặc 28 tháng 10 |
| Yêu cầu trình diễn/bảo vệ | Không biết |

## Định hướng trong tài liệu nhóm

- Mục tiêu: xây dựng và giải thích được kiến trúc của hệ thống đăng ký học phần có kiểm tra ràng buộc chính xác; không đăng ký trùng và không vượt sĩ số khi có yêu cầu đồng thời.
- Phạm vi nghiệp vụ: đăng nhập; xem thông tin bản thân; xem đợt đăng ký, lớp học phần và lượt đăng ký; đăng ký một hoặc nhiều lớp (tất cả-hoặc-không); hủy; quản trị cập nhật lớp và đợt; kiểm tra sức khỏe; kiểm thử và báo cáo tải.
- Dữ liệu: PostgreSQL, có bảng `idempotency_record` để gửi lại có khóa bền vững.
- Mục tiêu tải (mong muốn, không phải chỉ tiêu): 10.000 người dùng đồng thời và 50.000 yêu cầu, hiểu là tổng số yêu cầu theo kịch bản chứ không phải 50.000 yêu cầu/giây. Ngưỡng đạt và thời lượng phát tải chưa bàn tới.
- Pha 1: REST API, OpenAPI 3.x, Swagger UI; xử lý đồng bộ, không Redis, không hàng đợi; không xây giao diện người dùng riêng; chạy qua Tailscale trên bốn máy, không mở ra Internet công cộng.
- Lịch học: `section.session` là bitmask 28 bit, biểu diễn được lớp học 2 hoặc 3 buổi mỗi tuần và kiểm trùng ca.
- Giới hạn đã thừa nhận: chưa kiểm được tiên quyết đã đạt (thiếu kết quả học tập) và giờ học chi tiết như giờ bắt đầu, giờ kết thúc, phòng học (thiếu bảng lịch).
- Quản trị đăng nhập mọi lúc, trừ trong khung đăng ký của đợt đang mở; hủy không có lượt khớp trả 404.
- Tiêu chí nghiệm thu chung của nhóm: tính đúng, nhất quán, bảo mật, đo lường, triển khai và tính học thuật của báo cáo. Chưa có bằng chứng đây là rubric của giảng viên.

## Nguồn và xác nhận

| Nguồn | Nội dung |
| --- | --- |
| [Bản ghi công việc](https://docs.google.com/document/d/1uwnmNgnqrqivjJw1sukXS6l7eAkRvkxVJA-TKm8EeGI/edit) | Phân công, mốc, quy tắc làm việc, checklist pha 1 |
| [SRS Hệ thống đăng ký tín chỉ VNU](https://docs.google.com/document/d/1ZvEr9azlOUA6bg8tzyHFdqxTo_WlhHvptJu4ez4TIFQ/edit) | Mô hình dữ liệu, yêu cầu chức năng, quy tắc nghiệp vụ, giao dịch, bộ kiểm thử TC01–TC14 |
| [Đặc tả API](https://docs.google.com/document/d/1ycmTZJT62C24uzviFLZEwMOTwsi6tX8VYCGjw0bBRLQ/edit) | Chi tiết 10 điểm cuối, mã lỗi, idempotency |
| `docs/so-do-co-so-du-lieu.html` | Sơ đồ cơ sở dữ liệu | Chuẩn dữ liệu của MVP |
| [Đề bài `2026.md`](https://github.com/maytinhdibo/KTPM-architecture-solution/blob/main/2026.md) | Nội dung đề bài và gợi ý Pha 2 |
