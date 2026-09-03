package com.example.transit.service.auth;

import java.time.Duration;

/**
 * Refresh Token의 서버측 저장소. "탈취 대비 저장 및 검증"(기획안)을 위해, 발급한 Refresh Token을
 * 그대로 믿지 않고 서버가 들고 있는 값과 대조한다. 로그아웃·회전·재사용 감지 시 즉시 폐기할 수 있다.
 * <p>
 * 운영은 Redis({@code RedisRefreshTokenStore}), 테스트는 인메모리 페이크
 * ({@code InMemoryRefreshTokenStore})로 바꿔 끼운다 - 기존 {@code LastTrainLookup} 페이크 패턴과 같다.
 * <p>
 * 저장하는 값은 토큰 원문이 아니라 해시다. 저장소가 통째로 유출돼도 그것만으로는 유효한 토큰을
 * 만들 수 없게 한다.
 *
 * @see JwtProvider
 */
public interface RefreshTokenStore {

    /** 사용자의 특정 토큰(jti)을 저장한다. 같은 jti면 덮어쓴다. */
    void save(long userId, String jti, String tokenHash, Duration ttl);

    /** 저장된 해시와 일치하고 아직 살아있으면 true. */
    boolean matches(long userId, String jti, String tokenHash);

    /** 특정 토큰 하나만 폐기(회전 시 이전 토큰, 로그아웃). */
    void revoke(long userId, String jti);

    /** 사용자의 모든 Refresh Token 폐기(재사용 감지 = 탈취 의심 시). */
    void revokeAll(long userId);
}
