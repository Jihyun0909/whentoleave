package com.example.transit.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 토큰이 없거나 유효하지 않아 인증이 안 된 요청 → 401 JSON. */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        RestAuthErrorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                "UNAUTHORIZED", "인증이 필요합니다");
    }
}
