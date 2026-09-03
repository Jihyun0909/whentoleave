package com.example.transit.service.auth;

/**
 * 인증 흐름에서 클라이언트에게 4xx로 돌려줄 실패. 구체 사유는 {@link Reason}로 구분한다.
 */
public class AuthException extends RuntimeException {

    public enum Reason {
        /** 이미 가입된 이메일 (409) */
        EMAIL_ALREADY_USED,
        /** 이메일/비밀번호 불일치 (401) */
        INVALID_CREDENTIALS,
        /** Refresh Token이 유효하지 않거나 서버 저장본과 불일치 (401) */
        INVALID_REFRESH_TOKEN,
        /** 이미 쓴 Refresh Token 재사용 감지 → 해당 사용자 전체 토큰 폐기됨 (401) */
        REFRESH_TOKEN_REUSE_DETECTED
    }

    private final Reason reason;

    public AuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
