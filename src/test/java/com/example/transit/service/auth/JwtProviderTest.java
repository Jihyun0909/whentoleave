package com.example.transit.service.auth;

import com.example.transit.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtProviderTest {

    private static final String SECRET = "test-only-jwt-secret-0123456789-abcdefghij";

    private final JwtProvider jwt = new JwtProvider(new JwtProperties(
            SECRET, Duration.ofMinutes(15), Duration.ofDays(7), "whentoleave"));

    @Test
    void access_token은_userId와_role_partnerId를_담고_되파싱된다() {
        String token = jwt.createAccessToken(7L, Role.PARTNER_ADMIN, 3L);

        JwtProvider.AccessTokenClaims claims = jwt.parseAccessToken(token);

        assertEquals(7L, claims.userId());
        assertEquals(Role.PARTNER_ADMIN, claims.role());
        assertEquals(3L, claims.partnerId());
    }

    @Test
    void partnerId가_없으면_null로_파싱된다() {
        JwtProvider.AccessTokenClaims claims = jwt.parseAccessToken(jwt.createAccessToken(1L, Role.USER, null));
        assertNull(claims.partnerId());
    }

    @Test
    void refresh_token은_userId와_jti를_담는다() {
        JwtProvider.IssuedRefreshToken issued = jwt.createRefreshToken(42L);

        JwtProvider.RefreshTokenClaims claims = jwt.parseRefreshToken(issued.token());

        assertEquals(42L, claims.userId());
        assertEquals(issued.jti(), claims.jti());
    }

    @Test
    void refresh_token을_access로_파싱하면_거부된다() {
        String refresh = jwt.createRefreshToken(1L).token();
        assertThrows(InvalidTokenException.class, () -> jwt.parseAccessToken(refresh));
    }

    @Test
    void access_token을_refresh로_파싱하면_거부된다() {
        String access = jwt.createAccessToken(1L, Role.USER, null);
        assertThrows(InvalidTokenException.class, () -> jwt.parseRefreshToken(access));
    }

    @Test
    void 다른_키로_서명된_토큰은_거부된다() {
        JwtProvider other = new JwtProvider(new JwtProperties(
                "another-secret-9876543210-zyxwvutsrq-po", Duration.ofMinutes(15), Duration.ofDays(7), "whentoleave"));
        String token = other.createAccessToken(1L, Role.USER, null);

        assertThrows(InvalidTokenException.class, () -> jwt.parseAccessToken(token));
    }

    @Test
    void 만료된_access_token은_거부된다() throws InterruptedException {
        JwtProvider shortLived = new JwtProvider(new JwtProperties(
                SECRET, Duration.ofMillis(1), Duration.ofDays(7), "whentoleave"));
        String token = shortLived.createAccessToken(1L, Role.USER, null);

        Thread.sleep(20);

        assertThrows(InvalidTokenException.class, () -> shortLived.parseAccessToken(token));
    }

    @Test
    void hash는_같은_입력에_같은_값_다른_입력에_다른_값을_준다() {
        String token = jwt.createRefreshToken(1L).token();
        assertEquals(jwt.hash(token), jwt.hash(token));
        assertNotEquals(jwt.hash(token), jwt.hash(token + "x"));
    }

    @Test
    void 짧은_시크릿은_기동_시_거부된다() {
        assertThrows(IllegalStateException.class, () -> new JwtProvider(new JwtProperties(
                "too-short", Duration.ofMinutes(15), Duration.ofDays(7), "whentoleave")));
    }
}
