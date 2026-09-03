package com.example.transit.service.support;

/** 낙관적 락 충돌 재시도를 최대 횟수만큼 했는데도 성공하지 못했을 때(극단적 경합). */
public class RetryExhaustedException extends RuntimeException {

    public RetryExhaustedException(int attempts, Throwable cause) {
        super("동시 요청 경합으로 " + attempts + "회 재시도 후에도 처리하지 못했습니다", cause);
    }
}
