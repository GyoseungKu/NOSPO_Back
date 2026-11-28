package kr.nospo.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiErrors {
    public static Map<String, Object> body(String code) {
        return Map.of(
                "error",
                Map.of("code", code, "message", "요청을 처리할 수 없습니다.", "details", List.of()),
                "meta",
                Map.of("requestId", UUID.randomUUID()));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge(Exception ex) {
        return ResponseEntity.status(413)
                .header("Cache-Control", "no-store")
                .body(body("IMAGE_TOO_LARGE"));
    }

    @ExceptionHandler(
            org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ResponseEntity<?> missingPart(Exception ex) {
        return ResponseEntity.badRequest()
                .header("Cache-Control", "no-store")
                .body(body("INVALID_REQUEST"));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> handle(ApiException ex) {
        var builder = ResponseEntity.status(ex.status())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store");
        if (ex.retryAfter() > 0) builder.header("Retry-After", Long.toString(ex.retryAfter()));
        return builder.body(body(ex.code()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<?> invalidFields(Exception ex) {
        return ResponseEntity.status(422)
                .header("Cache-Control", "no-store")
                .body(body("VALIDATION_ERROR"));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<?> invalid(Exception ex) {
        return ResponseEntity.badRequest()
                .header("Cache-Control", "no-store")
                .body(body("INVALID_REQUEST"));
    }

    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException.class)
    ResponseEntity<?> retry(org.springframework.dao.ConcurrencyFailureException ex) {
        return ResponseEntity.status(409)
                .header("Retry-After", "1")
                .header("Cache-Control", "no-store")
                .body(body("REQUEST_IN_PROGRESS"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex, HttpServletRequest request) {
        if ((ex instanceof org.springframework.web.multipart.MultipartException
                        || ex instanceof HttpMediaTypeNotSupportedException)
                && "POST".equals(request.getMethod())
                && (request.getContextPath() + "/api/v1/admin/notices/images").equals(request.getRequestURI())) {
            return invalid(ex);
        }
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class)
                .error("API failure type={}", ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .header("Cache-Control", "no-store")
                .body(body("INTERNAL_ERROR"));
    }
}
