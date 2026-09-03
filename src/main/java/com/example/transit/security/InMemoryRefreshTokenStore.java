package com.example.transit.security;

import com.example.transit.service.auth.RefreshTokenStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트·로컬용 Refresh Token 저장소. Redis 없이 돈다({@code app.refresh-token.store=memory}).
 * 만료도 흉내 내서(저장 시각 + ttl) {@link #matches}에서 만료된 항목을 걸러낸다.
 */
@Component
@ConditionalOnProperty(name = "app.refresh-token.store", havingValue = "memory")
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private record Entry(String hash, Instant expiresAt) {
    }

    private final Map<Long, Map<String, Entry>> store = new ConcurrentHashMap<>();

    @Override
    public void save(long userId, String jti, String tokenHash, Duration ttl) {
        store.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(jti, new Entry(tokenHash, Instant.now().plus(ttl)));
    }

    @Override
    public boolean matches(long userId, String jti, String tokenHash) {
        Map<String, Entry> byJti = store.get(userId);
        if (byJti == null) {
            return false;
        }
        Entry entry = byJti.get(jti);
        if (entry == null) {
            return false;
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            byJti.remove(jti);
            return false;
        }
        return MessageDigest.isEqual(
                entry.hash().getBytes(StandardCharsets.UTF_8),
                tokenHash.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void revoke(long userId, String jti) {
        Map<String, Entry> byJti = store.get(userId);
        if (byJti != null) {
            byJti.remove(jti);
        }
    }

    @Override
    public void revokeAll(long userId) {
        store.remove(userId);
    }
}
