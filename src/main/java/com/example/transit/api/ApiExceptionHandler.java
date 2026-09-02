package com.example.transit.api;

import com.example.transit.api.dto.ErrorResponse;
import com.example.transit.service.auth.AuthException;
import com.example.transit.service.point.InsufficientPointException;
import com.example.transit.service.ride.RideException;
import com.example.transit.service.settlement.InvalidSettlementRequestException;
import com.example.transit.service.support.RetryExhaustedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * {@code /api/**} 응답을 JSON 에러 본문({@link ErrorResponse})으로 통일한다. 화면(web) 쪽 에러 처리
 * ({@code GlobalWebExceptionHandler}, redirect 반환)와 분리해서, 이 advice는
 * {@code com.example.transit.api} 패키지 컨트롤러에만 적용한다.
 * <p>
 * SecurityFilterChain 단계에서 나는 401/403은 이 advice까지 오지 않으므로
 * {@code RestAuthenticationEntryPoint}/{@code RestAccessDeniedHandler}가 같은 형태로 처리한다.
 */
@RestControllerAdvice(basePackages = "com.example.transit.api")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RetryExhaustedException.class)
    public ResponseEntity<ErrorResponse> onRetryExhausted(RetryExhaustedException e) {
        log.warn("동시성 재시도 소진", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("CONCURRENCY_RETRY_EXHAUSTED", "잠시 후 다시 시도해 주세요"));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> onAuth(AuthException e) {
        HttpStatus status = switch (e.reason()) {
            case EMAIL_ALREADY_USED -> HttpStatus.CONFLICT;
            case INVALID_CREDENTIALS, INVALID_REFRESH_TOKEN, REFRESH_TOKEN_REUSE_DETECTED -> HttpStatus.UNAUTHORIZED;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(e.reason().name(), e.getMessage()));
    }

    @ExceptionHandler(RideException.class)
    public ResponseEntity<ErrorResponse> onRide(RideException e) {
        HttpStatus status = switch (e.reason()) {
            case RIDE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PARTNER_NOT_AVAILABLE, INVALID_FARE, INVALID_POINT_AMOUNT -> HttpStatus.BAD_REQUEST;
            case ILLEGAL_STATE -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(e.reason().name(), e.getMessage()));
    }

    @ExceptionHandler(InsufficientPointException.class)
    public ResponseEntity<ErrorResponse> onInsufficientPoint(InsufficientPointException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ErrorResponse("INSUFFICIENT_POINT", e.getMessage()));
    }

    @ExceptionHandler(InvalidSettlementRequestException.class)
    public ResponseEntity<ErrorResponse> onInvalidSettlementRequest(InvalidSettlementRequestException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_SETTLEMENT_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> onNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_FAILED", detail));
    }

    /** 파싱 불가한 본문(깨진 JSON, 타입 불일치 등)도 {@link ErrorResponse} 형태로 통일한다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("MALFORMED_REQUEST", "요청 본문을 읽을 수 없습니다"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> onUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 Content-Type 입니다"));
    }
}
