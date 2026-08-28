package com.example.transit.service.auth;

/**
 * 로그인·재발급 결과로 내려주는 토큰 한 쌍.
 *
 * @param accessToken            짧은 수명(15분)의 API 인증용 토큰
 * @param refreshToken           긴 수명(7일)의 재발급용 토큰. 회전되므로 매번 새 값이다.
 * @param accessTokenExpiresIn   accessToken 남은 수명(초). 클라이언트가 갱신 타이밍을 잡는 데 쓴다.
 */
public record TokenPair(String accessToken, String refreshToken, long accessTokenExpiresIn) {
}
