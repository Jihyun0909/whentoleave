package com.example.transit.api;

import com.example.transit.service.auth.AuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * {@code /api/**} 응답을 JSON 에러 본문으로 통일한다. 화면(web) 쪽 에러 처리
 * ({@code GlobalWebExceptionHandler}, redirect 반환)와 분리해서, 이 advice는
 * {@code com.example.transit.api} 패키지 컨트롤러에만 적용한다.
 */
@RestControllerAdvice(basePackages = "com.example.transit.api")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ErrorResponse(String code, String message) {
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> onAuth(AuthException e) {
        HttpStatus status = switch (e.reason()) {
            case EMAIL_ALREADY_USED -> HttpStatus.CONFLICT;
            case INVALID_CREDENTIALS, INVALID_REFRESH_TOKEN, REFRESH_TOKEN_REUSE_DETECTED -> HttpStatus.UNAUTHORIZED;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(e.reason().name(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_FAILED", detail));
    }
}
