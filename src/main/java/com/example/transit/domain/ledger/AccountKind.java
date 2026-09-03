package com.example.transit.domain.ledger;

/**
 * 원장 계정의 종류. 각 종류는 "정상 잔액(normal balance)"이 차변인지 대변인지로 나뉘고,
 * 이게 분개가 잔액을 늘리는지 줄이는지를 결정한다({@link #debitNormal()}).
 * <p>
 * 포인트(B2C):
 * <ul>
 *   <li>{@link #POINT} — 사용자별 포인트 지갑. 플랫폼이 사용자에게 진 빚이므로 대변 정상.</li>
 *   <li>{@link #POINT_CONTRA} — 그 빚의 거울이 되는 시스템 단일 계정. 차변 정상.
 *       불변식: {@code POINT_CONTRA.balance == Σ(모든 POINT.balance)}.</li>
 * </ul>
 * 정산(B2B):
 * <ul>
 *   <li>{@link #CASH} — 제휴사별 가상 계좌 잔액(정산으로 지급된 몫). 대변 정상.</li>
 *   <li>{@link #COMMISSION_INCOME} — 플랫폼 수수료 수익(시스템). 대변 정상.</li>
 *   <li>{@link #FARE_CLEARING} — 정산의 자금원(시스템). 결제의 현금 흐름은 외부 PG로 처리했다고
 *       가정해 원장 밖이고, 정산 시 이 계정을 차변으로 상계한다. 차변 정상.</li>
 * </ul>
 */
public enum AccountKind {
    POINT(false),
    POINT_CONTRA(true),
    CASH(false),
    COMMISSION_INCOME(false),
    FARE_CLEARING(true);

    private final boolean debitNormal;

    AccountKind(boolean debitNormal) {
        this.debitNormal = debitNormal;
    }

    /** 차변 정상 계정이면 true. DEBIT 분개가 잔액을 늘리고 CREDIT이 줄인다. 대변 정상은 그 반대. */
    public boolean debitNormal() {
        return debitNormal;
    }
}
