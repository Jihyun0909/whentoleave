package com.example.transit.service.auth;

import com.example.transit.domain.AppUser;
import com.example.transit.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입 · 로그인 · 토큰 재발급 · 로그아웃. HTTP·Security 프레임워크 타입을 모른다
 * (컨트롤러가 요청 본문에서 꺼낸 문자열만 받는다).
 * <p>
 * Refresh Token은 발급할 때마다 회전한다: 재발급 시 이전 토큰을 폐기하고 새 쌍을 준다.
 * 이미 폐기된(= 회전됐거나 로그아웃된) Refresh Token이 다시 들어오면 탈취로 보고
 * 그 사용자의 모든 Refresh Token을 폐기한다.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwt;
    private final RefreshTokenStore refreshTokenStore;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder,
                       JwtProvider jwt, RefreshTokenStore refreshTokenStore) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.refreshTokenStore = refreshTokenStore;
    }

    @Transactional
    public long signup(String email, String rawPassword) {
        String normalizedEmail = email.trim().toLowerCase();
        if (users.existsByEmail(normalizedEmail)) {
            throw new AuthException(AuthException.Reason.EMAIL_ALREADY_USED, "이미 가입된 이메일입니다");
        }
        AppUser saved = users.save(AppUser.newUser(normalizedEmail, passwordEncoder.encode(rawPassword)));
        return saved.getId();
    }

    public TokenPair login(String email, String rawPassword) {
        AppUser user = users.findByEmail(email.trim().toLowerCase())
                .filter(u -> passwordEncoder.matches(rawPassword, u.getPasswordHash()))
                .orElseThrow(() -> new AuthException(
                        AuthException.Reason.INVALID_CREDENTIALS, "이메일 또는 비밀번호가 올바르지 않습니다"));
        return issue(user);
    }

    public TokenPair refresh(String refreshToken) {
        JwtProvider.RefreshTokenClaims claims;
        try {
            claims = jwt.parseRefreshToken(refreshToken);
        } catch (InvalidTokenException e) {
            throw new AuthException(AuthException.Reason.INVALID_REFRESH_TOKEN, "유효하지 않은 Refresh Token 입니다");
        }

        if (!refreshTokenStore.matches(claims.userId(), claims.jti(), jwt.hash(refreshToken))) {
            refreshTokenStore.revokeAll(claims.userId());
            log.warn("Refresh Token 재사용 감지 - 전체 세션 폐기. userId={}, jti={}", claims.userId(), claims.jti());
            throw new AuthException(AuthException.Reason.REFRESH_TOKEN_REUSE_DETECTED,
                    "재사용이 감지되어 모든 세션을 종료했습니다. 다시 로그인하세요");
        }

        AppUser user = users.findById(claims.userId())
                .orElseThrow(() -> new AuthException(
                        AuthException.Reason.INVALID_REFRESH_TOKEN, "존재하지 않는 사용자입니다"));

        refreshTokenStore.revoke(claims.userId(), claims.jti());
        return issue(user);
    }

    public void logout(String refreshToken) {
        try {
            JwtProvider.RefreshTokenClaims claims = jwt.parseRefreshToken(refreshToken);
            refreshTokenStore.revoke(claims.userId(), claims.jti());
        } catch (InvalidTokenException ignored) {
            // 이미 못 쓰는 토큰으로 로그아웃을 시도해도 결과적으로 "로그아웃됨"이므로 조용히 성공 처리
        }
    }

    private TokenPair issue(AppUser user) {
        String accessToken = jwt.createAccessToken(user.getId(), user.getRole(), user.getPartnerId());
        JwtProvider.IssuedRefreshToken refresh = jwt.createRefreshToken(user.getId());
        refreshTokenStore.save(user.getId(), refresh.jti(), jwt.hash(refresh.token()), jwt.refreshTokenTtl());
        return new TokenPair(accessToken, refresh.token(), jwt.accessTokenTtl().toSeconds());
    }
}
