package com.example.transit.domain;

/** 제휴사 일일 정산의 상태. */
public enum SettlementStatus {
    /** 계산은 됐고 확정 전 */
    PENDING,
    /** 원장 반영 + 결제 정산 마킹 완료 */
    DONE,
    /** 처리 실패(부분 실패). 해당 파트너의 이번 회차는 롤백됨 - 다음 실행에서 재시도된다. */
    FAILED
}
