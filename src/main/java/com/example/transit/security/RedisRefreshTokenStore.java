package com.example.transit.security;

import com.example.transit.service.auth.RefreshTokenStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * 운영용 Refresh Token 저장소. 사용자별로 Redis Hash 하나({@code rt:{userId}})를 두고
 * 필드 = jti, 값 = 토큰 해시로 저장한다.
 * <p>
 * TTL은 Hash 키 전체에 건다(필드 단위 만료를 쓰지 않는다). Refresh Token 수명이 전부 같고
 * 재발급마다 회전되므로, 저장할 때마다 키 만료를 갱신하는 것으로 충분하다 - 단, 로그인 간격이
 * 벌어지면 오래된 jti가 개별 수명보다 조금 더 살아있을 수 있다(회전 대상이라 실사용 영향 없음).
 */
@Component
@ConditionalOnProperty(name = "app.refresh-token.store", havingValue = "redis", matchIfMissing = true)
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private final StringRedisTemplate redis;

    public RedisRefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(long userId) {
        return "rt:" + userId;
    }

    @Override
    public void save(long userId, String jti, String tokenHash, Duration ttl) {
        String key = key(userId);
        redis.opsForHash().put(key, jti, tokenHash);
        redis.expire(key, ttl);
    }

    @Override
    public boolean matches(long userId, String jti, String tokenHash) {
        Object stored = redis.opsForHash().get(key(userId), jti);
        return stored != null && MessageDigest.isEqual(
                stored.toString().getBytes(StandardCharsets.UTF_8),
                tokenHash.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void revoke(long userId, String jti) {
        redis.opsForHash().delete(key(userId), jti);
    }

    @Override
    public void revokeAll(long userId) {
        redis.delete(key(userId));
    }
}
