package com.example.transit.service.settlement;

/**
 * 한 제휴사를 정산할 수 없을 때(비활성, 수수료율 범위 밖 등). 배치 스텝이 이 예외를 skip으로
 * 처리해서 해당 제휴사의 청크만 롤백하고 나머지 제휴사는 계속 진행한다.
 */
public class SettlementException extends RuntimeException {

    private final long partnerId;

    public SettlementException(long partnerId, String message) {
        super(message);
        this.partnerId = partnerId;
    }

    public long partnerId() {
        return partnerId;
    }
}
