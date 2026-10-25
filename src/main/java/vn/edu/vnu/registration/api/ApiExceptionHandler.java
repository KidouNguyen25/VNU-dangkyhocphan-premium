package vn.edu.vnu.registration.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.Map;
import static vn.edu.vnu.registration.domain.Models.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorBody> business(BusinessException e) {
        var response = ResponseEntity.status(e.status()).header("Cache-Control", "no-store");
        if (e.status() == 401) response.header("WWW-Authenticate", "Bearer");
        if (e.status() == 429 && e.body().details() instanceof Map<?, ?> details) response.header("Retry-After", String.valueOf(details.get("retryAfter")));
        return response.body(e.body());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, ConstraintViolationException.class,
        MissingRequestValueException.class, MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class})
    ResponseEntity<ErrorBody> validation(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_ERROR", "Dữ liệu đầu vào không hợp lệ.", null));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorBody> notFound() { return ResponseEntity.status(404).body(new ErrorBody("NOT_FOUND", "Không tìm thấy tài nguyên.", null)); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorBody> method() { return ResponseEntity.status(405).body(new ErrorBody("METHOD_NOT_ALLOWED", "Phương thức không được hỗ trợ.", null)); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> media() { return ResponseEntity.status(415).body(new ErrorBody("UNSUPPORTED_MEDIA_TYPE", "Cần Content-Type application/json.", null)); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorBody> database(DataAccessException e) {
        // Không ghi exception DB: thông điệp có thể chứa giá trị truy vấn hoặc chuỗi kết nối.
        LOG.error("requestId={} databaseFailure={}", MDC.get("requestId"), e.getClass().getSimpleName());
        return ResponseEntity.status(503).body(new ErrorBody("SERVICE_UNAVAILABLE", "Không thể truy cập cơ sở dữ liệu, vui lòng thử lại.", null));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        LOG.error("requestId={} internalFailure={}", MDC.get("requestId"), e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new ErrorBody("INTERNAL_ERROR", "Có lỗi nội bộ, vui lòng thử lại.", null));
    }
}
