# VNU-dangkyhocphan-premium
Bài tập lớn môn học Kiến trúc phần mềm

Backend K5 dùng **Java 17, Spring Boot 3.5, Spring Security, Spring JDBC và PostgreSQL**. API → nghiệp vụ → data mapper; tầng nghiệp vụ không import Spring, HTTP hoặc JDBC. Pha 1 không có frontend riêng.

JWT do đồng đội triển khai qua `Ports.TokenGateway`. Nhánh này đã chuẩn bị đăng nhập, middleware và phân quyền; khi chưa có bean JWT, thao tác cần xác minh/ký token trả `503 AUTH_NOT_CONFIGURED`. Không có token giả hoặc cách bỏ qua xác thực trong mã chạy thật.

## Chạy và kiểm thử

Cần JDK 17+. Repo có Maven Wrapper (tự tải Maven 3.9.9); cũng có thể dùng Maven 3.9+ đã cài. Cấu hình biến môi trường theo [.env.example](.env.example); Spring Boot không tự đọc file `.env`. Tạo DB bằng migration của Quyền trước khi chạy API.

```powershell
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/dkhp'
$env:DB_USER = 'dkhp_api'
$env:DB_PASSWORD = '<mat-khau-cuc-bo>'
.\mvnw.cmd spring-boot:run
```

- Swagger UI: `http://127.0.0.1:8080/swagger-ui/index.html`
- OpenAPI: `http://127.0.0.1:8080/v3/api-docs`
- Health: `http://127.0.0.1:8080/api/v1/health`, giới hạn IP, không dùng JWT.

```sh
./mvnw test
./mvnw package
java -jar target/course-registration-0.1.0-SNAPSHOT.jar
```

Windows dùng `.\mvnw.cmd test` và `.\mvnw.cmd package`. Bộ test khởi tạo PostgreSQL thật tạm thời, tạo sáu bảng fixture, chạy với tài khoản API quyền hạn chế và SQL hậu kiểm. Không cần Docker hoặc DB chung; bộ kiểm thử không kết nối DB triển khai. Trên Windows cần runtime Visual C++ phù hợp với PostgreSQL embedded. Token kiểm thử chỉ ở `src/test`.

Chi tiết: [bàn giao K5, hợp đồng JWT, API và kiểm thử](docs/k5-backend.md). Đặc tả gốc: [phạm vi pha 1](docs/phase-1-scope.md), [sơ đồ DB](docs/so-do-co-so-du-lieu.html).
