package com.example.transit.api.dto.auth;

import com.example.transit.service.auth.TokenPair;

/**
 * @param tokenType 항상 "Bearer". 클라이언트가 {@code Authorization} 헤더를 만들 때 쓴다.
 * @param expiresIn accessToken 남은 수명(초)
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
    public static TokenResponse from(TokenPair pair) {
        return new TokenResponse(pair.accessToken(), pair.refreshToken(), "Bearer", pair.accessTokenExpiresIn());
    }
}
