package com.example.transit.domain;

/** 감사 로그에 남기는 사건의 종류. */
public enum AuditEvent {
    /** 잔액 부족으로 포인트 차감 거부 */
    INSUFFICIENT_POINT,
    /** 이미 처리된 idempotency_key로 분개 재시도 → 무시함 */
    DUPLICATE_IDEMPOTENCY,
    /** 허용되지 않는 이용/결제 상태 전이 시도 */
    ILLEGAL_STATE_TRANSITION,
    /** 분개 차변/대변 합이 안 맞음 (버그 신호) */
    LEDGER_IMBALANCE,
    /** 정산 실패 → 결제 취소·역분개 (PR C) */
    SETTLEMENT_FAILED
}
