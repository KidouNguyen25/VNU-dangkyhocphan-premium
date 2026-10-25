# Hệ thống đăng ký học phần VNU

Bài tập lớn môn Kiến trúc phần mềm.

## Giới thiệu

Backend cho hệ thống đăng ký học phần. Mục tiêu là xây dựng và giải thích được kiến trúc của một hệ thống đăng ký có kiểm tra ràng buộc chính xác: không đăng ký trùng và không vượt sĩ số khi nhiều sinh viên đăng ký đồng thời.

Dự án gồm hai pha:
- **Pha 1:** xây dựng hệ thống với các chức năng cơ bản.
- **Pha 2:** cải tiến theo các thuộc tính chất lượng, có đánh giá trước và sau cải tiến.

## Phạm vi Pha 1

- Đăng nhập, phát JWT 60 phút.
- Xem thông tin bản thân, đợt đăng ký, danh sách lớp học phần (lọc, phân trang), lượt đăng ký.
- Đăng ký một hoặc nhiều lớp trong một request (tất cả hoặc không), có `Idempotency-Key`.
- Hủy đăng ký.
- Quản trị: cập nhật đợt đăng ký và lớp học phần.
- Kiểm tra sức khỏe dịch vụ (`/api/v1/health`, chỉ nhóm phát triển).
- REST API, JSON, OpenAPI 3.x, Swagger UI. Không có giao diện người dùng riêng.

Chi tiết nghiệp vụ, ràng buộc và danh sách endpoint: [docs/phase-1-scope.md](docs/phase-1-scope.md).

## Kiến trúc và công nghệ

| Thành phần | Lựa chọn |
| --- | --- |
| Ngôn ngữ, framework | Java 21, Spring Boot |
| Cơ sở dữ liệu | PostgreSQL |
| Đóng gói | Docker, Docker Compose |
| Mạng | Tailscale, không mở ra Internet công cộng |

Hệ thống xử lý đồng bộ, không dùng Redis, hàng đợi hay worker. PostgreSQL là nguồn có thẩm quyền; quyết định nhận chỗ nằm trong một giao dịch ngắn. Phân tầng: API → Nghiệp vụ → Truy cập dữ liệu.

Sơ đồ kiến trúc: [docs/diagrams/kien-truc.mmd](docs/diagrams/kien-truc.mmd). 
Luồng đăng ký chi tiết (khóa, idempotency, rollback): [docs/diagrams/luong-dang-ky.mmd](docs/diagrams/luong-dang-ky.mmd).

### Luồng hoạt động trên kiến trúc

![Đường đi của một yêu cầu: máy khách, middleware, controller, service, repository, PostgreSQL, rồi phản hồi sau COMMIT](docs/assets/luong-kien-truc.gif)

Yêu cầu đi qua middleware (JWT, vai trò, giới hạn tốc độ), controller, service, repository rồi PostgreSQL. Phản hồi chỉ trả về sau khi giao dịch COMMIT.

### Luồng đăng ký

![Luồng POST /api/v1/enrollments: kiểm JWT, ghi idempotency_record, khóa account và section, kiểm 5 luật, UPDATE và INSERT, COMMIT, trả 201](docs/assets/luong-dang-ky.gif)

Đăng ký một hoặc nhiều lớp trong một giao dịch: ghi `idempotency_record`, khóa `account` rồi `section` theo `id`, kiểm 5 luật, tăng `enrolled_count` nếu còn chỗ, ghi `enrollment`, COMMIT rồi trả 201. Lỗi nghiệp vụ hoàn tác phần đăng ký và vẫn lưu phản hồi lỗi; lỗi 5xx hoàn tác toàn bộ.

### Luồng hủy

![Luồng DELETE /api/v1/enrollments/{id}: khóa account, kiểm đợt OPEN, tìm lượt ENROLLED, xóa, giảm enrolled_count, COMMIT, trả 204](docs/assets/luong-huy.gif)

Hủy trong một giao dịch: khóa `account`, kiểm đợt `OPEN`, tìm lượt `ENROLLED` của chính sinh viên, khóa `section`, xóa dòng, giảm `enrolled_count` đúng một lần, trả 204. Không có lượt khớp trả 404 `ENROLLMENT_NOT_FOUND`. Luồng hủy không dùng `idempotency_record`.

### Sơ đồ cơ sở dữ liệu

![Sơ đồ ERD gồm 6 bảng: account, idempotency_record, enrollment, course, section, registration_period](docs/assets/so-do-co-so-du-lieu.png)

6 bảng PostgreSQL: `account`, `course`, `registration_period`, `section`, `enrollment`, `idempotency_record`. Bản tương tác (rê chuột làm nổi quan hệ, các tab quyền, cách ghi và truy vấn, ràng buộc) nằm ở [docs/so-do-co-so-du-lieu.html](docs/so-do-co-so-du-lieu.html); tải về và mở bằng trình duyệt.

## Tài liệu

| Tài liệu | Nội dung |
| --- | --- |
| [docs/official-requirements.md](docs/official-requirements.md) | Đề bài và yêu cầu chính thức |
| [docs/phase-1-scope.md](docs/phase-1-scope.md) | Phạm vi, nghiệp vụ, API, ràng buộc |
| [docs/system-security.md](docs/system-security.md) | Phân tích bảo mật |
| [docs/open-decisions.md](docs/open-decisions.md) | Các quyết định của nhóm |
| [docs/diagrams/kien-truc.mmd](docs/diagrams/kien-truc.mmd) | Sơ đồ kiến trúc |
| [docs/diagrams/luong-dang-ky.mmd](docs/diagrams/luong-dang-ky.mmd) | Luồng đăng ký |
| [docs/diagrams/luong-huy.mmd](docs/diagrams/luong-huy.mmd) | Luồng hủy |
| [docs/so-do-co-so-du-lieu.html](docs/so-do-co-so-du-lieu.html) | Sơ đồ cơ sở dữ liệu (ERD), mở bằng trình duyệt |

## Cách chạy

Yêu cầu: Docker và Docker Compose.

1. Clone repo.
2. Tạo file cấu hình từ mẫu:
   ```
   cp .env.example .env
   ```
3. Sửa `.env`: đặt `POSTGRES_PASSWORD` và `JWT_SECRET` (sinh khóa bằng `openssl rand -base64 48`). Không commit `.env`.
4. Chạy:
   ```
   docker compose up --build
   ```
5. Mở Swagger UI của API (địa chỉ sẽ ghi khi backend sẵn sàng). Mặc định API bind `127.0.0.1:8080`. Khi triển khai qua Tailscale, đặt `API_BIND_ADDR` và `DB_BIND_ADDR` thành IP Tailscale của máy.

## Trạng thái

| Hạng mục | Trạng thái |
| --- | --- |
| Đề bài, phạm vi, quyết định | Có |
| Sơ đồ kiến trúc, luồng đăng ký và hủy, ERD | Có |
| Phân tích bảo mật | Có |
| `docker-compose.yml`, `.env.example` | Có, chưa chạy thử đầy đủ |
| `Dockerfile` cho API | Chưa |
| Khung project Spring Boot | Chưa |
| Endpoint nghiệp vụ, giao dịch, idempotency | Chưa |
| JWT, phân quyền, giới hạn tốc độ | Chưa |
| Migration, dữ liệu mẫu | Chưa |
| OpenAPI, Swagger UI | Chưa |
| CI (GitHub Actions) | Chưa |
| Kiểm thử đồng thời, idempotency, thử tải | Chưa |
