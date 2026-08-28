package com.example.transit.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * SecurityFilterChain 단계의 401/403을 컨트롤러 에러와 같은 {@code {code, message}} JSON으로 쓴다.
 * 이 단계는 MVC 밖이라 {@code @RestControllerAdvice}가 안 잡히므로 응답을 직접 쓴다.
 * 형태는 {@code com.example.transit.api.dto.ErrorResponse}와 맞춘다(코드·메시지 모두 고정 문자열).
 */
final class RestAuthErrorWriter {

    private RestAuthErrorWriter() {
    }

    static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
