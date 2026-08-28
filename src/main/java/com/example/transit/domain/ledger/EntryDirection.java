package com.example.transit.domain.ledger;

/** 분개 한 줄의 방향. 한 트랜잭션 안에서 Σ(DEBIT 금액) == Σ(CREDIT 금액) 이어야 한다. */
public enum EntryDirection {
    DEBIT,
    CREDIT
}
