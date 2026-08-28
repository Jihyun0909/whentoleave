package com.example.transit.service.auth;

import com.example.transit.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/**
 * JWT(Access/Refresh) 발급·검증. 순수 토큰 로직만 담당하고 HTTP·DB를 모른다.
 * <p>
 * Access와 Refresh는 같은 HS256 키로 서명하되 {@code typ} 클레임("access"/"refresh")으로
 * 구분한다 - Refresh Token을 API 인증에 그대로 쓰지 못하게(그 반대도) 막는다.
 */
@Component
public class JwtProvider {

    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final String issuer;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;

    public JwtProvider(JwtProperties properties) {
        byte[] secretBytes = properties.secret() == null
                ? new byte[0] : properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret 이 32바이트 미만입니다. JWT_SECRET 환경변수에 충분히 긴 값을 넣으세요.");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.issuer = properties.issuer();
        this.accessTokenTtl = properties.accessTokenTtl();
        this.refreshTokenTtl = properties.refreshTokenTtl();
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }

    public Duration refreshTokenTtl() {
        return refreshTokenTtl;
    }

    public String createAccessToken(long userId, Role role, Long partnerId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claim("typ", TYPE_ACCESS)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key);
        if (partnerId != null) {
            builder.claim("pid", partnerId);
        }
        return builder.compact();
    }

    /** @return 새 Refresh Token과 그 jti. jti는 {@link RefreshTokenStore}의 키로 쓰인다. */
    public IssuedRefreshToken createRefreshToken(long userId) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .id(jti)
                .claim("typ", TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenTtl)))
                .signWith(key)
                .compact();
        return new IssuedRefreshToken(token, jti);
    }

    /** @throws InvalidTokenException 서명 불일치·만료·타입 불일치 */
    public AccessTokenClaims parseAccessToken(String token) {
        Claims claims = parse(token, TYPE_ACCESS);
        Long partnerId = claims.get("pid", Long.class);
        Role role = Role.valueOf(claims.get("role", String.class));
        return new AccessTokenClaims(Long.parseLong(claims.getSubject()), role, partnerId);
    }

    /** @throws InvalidTokenException 서명 불일치·만료·타입 불일치 */
    public RefreshTokenClaims parseRefreshToken(String token) {
        Claims claims = parse(token, TYPE_REFRESH);
        return new RefreshTokenClaims(Long.parseLong(claims.getSubject()), claims.getId());
    }

    /** 저장소에는 원문 대신 이 해시를 넣는다. */
    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Claims parse(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!expectedType.equals(claims.get("typ", String.class))) {
                throw new InvalidTokenException("토큰 타입이 " + expectedType + "이 아닙니다");
            }
            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("유효하지 않은 토큰", e);
        }
    }

    public record IssuedRefreshToken(String token, String jti) {
    }

    public record AccessTokenClaims(long userId, Role role, Long partnerId) {
    }

    public record RefreshTokenClaims(long userId, String jti) {
    }
}
