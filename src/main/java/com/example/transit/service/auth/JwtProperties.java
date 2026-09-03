package com.example.transit.service.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 설정. 시크릿은 커밋하지 않고 환경변수({@code JWT_SECRET})로 주입한다.
 *
 * @param secret          HS256 서명 키. 최소 32바이트(256비트) 이상이어야 한다.
 * @param accessTokenTtl  Access Token 만료. 기획안 기준 15분.
 * @param refreshTokenTtl Refresh Token 만료. 기획안 기준 7일.
 * @param issuer          토큰 {@code iss} 클레임.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String issuer
) {
    public JwtProperties {
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(7);
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "whentoleave";
        }
    }
}
