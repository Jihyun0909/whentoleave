package com.example.transit.service.auth;

/** JWT 서명 불일치·만료·타입 불일치 등 토큰 자체가 유효하지 않을 때. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
