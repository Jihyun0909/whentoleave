package com.example.transit.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 인증은 됐지만 권한이 부족한 요청(예: USER가 /api/v1/admin 접근) → 403 JSON. */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        RestAuthErrorWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                "FORBIDDEN", "접근 권한이 없습니다");
    }
}
