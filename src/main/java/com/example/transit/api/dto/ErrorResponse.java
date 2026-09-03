package com.example.transit.api.dto;

/**
 * {@code /api/**}의 공통 에러 응답 본문. 컨트롤러에서 던진 예외
 * ({@code ApiExceptionHandler})든 SecurityFilterChain의 401/403
 * ({@code RestAuthenticationEntryPoint}, {@code RestAccessDeniedHandler})든
 * 같은 {@code {code, message}} 형태로 내려간다.
 *
 * @param code    기계가 분기할 수 있는 식별자 (예: EMAIL_ALREADY_USED, UNAUTHORIZED)
 * @param message 사람이 읽는 설명
 */
public record ErrorResponse(String code, String message) {
}
