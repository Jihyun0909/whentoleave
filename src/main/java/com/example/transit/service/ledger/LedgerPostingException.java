package com.example.transit.service.ledger;

/** 분개가 성립하지 않을 때(차·대변 불균형, 금액 0 이하, 줄 수 부족 등) — 프로그래밍 오류에 가깝다. */
public class LedgerPostingException extends RuntimeException {

    public LedgerPostingException(String message) {
        super(message);
    }
}
