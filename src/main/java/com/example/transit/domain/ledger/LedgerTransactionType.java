package com.example.transit.domain.ledger;

/** 원장 트랜잭션(분개 묶음)의 성격. */
public enum LedgerTransactionType {
    /** 택시 이용 완료 시 5% 포인트 적립 */
    PAYBACK,
    /** 결제 시 선불 포인트 차감 */
    SPEND,
    /** 제휴사 수수료 정산 — PR C */
    SETTLEMENT,
    /** 앞선 트랜잭션을 상계하는 역분개(수정·삭제 대신) */
    REVERSE
}
