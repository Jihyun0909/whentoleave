package com.example.transit.domain.ledger;

/**
 * 원장 계정의 종류. 각 종류는 "정상 잔액(normal balance)"이 차변인지 대변인지로 나뉘고,
 * 이게 분개가 잔액을 늘리는지 줄이는지를 결정한다({@link #debitNormal()}).
 * <p>
 * 포인트 시스템은 두 계정으로 닫힌다:
 * <ul>
 *   <li>{@link #POINT} — 사용자별 포인트 지갑. 플랫폼이 사용자에게 진 빚이므로 대변 정상(늘 때 CREDIT).</li>
 *   <li>{@link #POINT_CONTRA} — 그 빚의 거울이 되는 시스템 단일 계정. 차변 정상.
 *       불변식: {@code POINT_CONTRA.balance == Σ(모든 POINT.balance)}.</li>
 * </ul>
 * (제휴사 CASH·수수료수익 계정은 PR C에서 추가)
 */
public enum AccountKind {
    POINT(false),
    POINT_CONTRA(true);

    private final boolean debitNormal;

    AccountKind(boolean debitNormal) {
        this.debitNormal = debitNormal;
    }

    /** 차변 정상 계정이면 true. DEBIT 분개가 잔액을 늘리고 CREDIT이 줄인다. 대변 정상은 그 반대. */
    public boolean debitNormal() {
        return debitNormal;
    }
}
