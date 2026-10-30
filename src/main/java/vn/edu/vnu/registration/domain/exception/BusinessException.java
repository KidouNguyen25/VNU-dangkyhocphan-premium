package vn.edu.vnu.registration.domain.exception;

import vn.edu.vnu.registration.domain.Models;

import java.util.Map;

public class BusinessException extends RuntimeException {
    private final int status;
    private final ErrorBody body;

    public BusinessException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public BusinessException(int status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.body = new ErrorBody(code, message, details);
    }

    public int status() { return status; }

    public ErrorBody body() { return body; }

    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, code, message);
    }
    public static BusinessException sectionError(int status, String code, String message, long id) {
        return new BusinessException(status, code, message, Map.of("sectionId", id));
    }
}
