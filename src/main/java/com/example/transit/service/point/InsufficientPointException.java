package com.example.transit.service.point;

/** 선불 포인트 잔액보다 많이 차감하려 할 때. */
public class InsufficientPointException extends RuntimeException {

    private final long requested;
    private final long balance;

    public InsufficientPointException(long requested, long balance) {
        super("포인트가 부족합니다 (요청 " + requested + ", 잔액 " + balance + ")");
        this.requested = requested;
        this.balance = balance;
    }

    public long requested() {
        return requested;
    }

    public long balance() {
        return balance;
    }
}
