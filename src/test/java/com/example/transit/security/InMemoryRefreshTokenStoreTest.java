package com.example.transit.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRefreshTokenStoreTest {

    private final InMemoryRefreshTokenStore store = new InMemoryRefreshTokenStore();

    @Test
    void 저장한_해시와_일치하면_matches_true() {
        store.save(1L, "jti-a", "hash-a", Duration.ofDays(7));
        assertTrue(store.matches(1L, "jti-a", "hash-a"));
    }

    @Test
    void 해시가_다르면_matches_false() {
        store.save(1L, "jti-a", "hash-a", Duration.ofDays(7));
        assertFalse(store.matches(1L, "jti-a", "other"));
    }

    @Test
    void revoke는_해당_토큰만_지운다() {
        store.save(1L, "jti-a", "hash-a", Duration.ofDays(7));
        store.save(1L, "jti-b", "hash-b", Duration.ofDays(7));

        store.revoke(1L, "jti-a");

        assertFalse(store.matches(1L, "jti-a", "hash-a"));
        assertTrue(store.matches(1L, "jti-b", "hash-b"));
    }

    @Test
    void revokeAll은_사용자의_모든_토큰을_지운다() {
        store.save(1L, "jti-a", "hash-a", Duration.ofDays(7));
        store.save(1L, "jti-b", "hash-b", Duration.ofDays(7));

        store.revokeAll(1L);

        assertFalse(store.matches(1L, "jti-a", "hash-a"));
        assertFalse(store.matches(1L, "jti-b", "hash-b"));
    }

    @Test
    void 만료된_토큰은_matches_false() {
        store.save(1L, "jti-a", "hash-a", Duration.ofMillis(-1));
        assertFalse(store.matches(1L, "jti-a", "hash-a"));
    }
}
