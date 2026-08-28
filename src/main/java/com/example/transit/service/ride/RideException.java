package com.example.transit.service.ride;

/** 이용/결제 흐름에서 클라이언트에게 4xx로 돌려줄 실패. */
public class RideException extends RuntimeException {

    public enum Reason {
        /** 존재하지 않거나 내 것이 아닌 이용 (404) */
        RIDE_NOT_FOUND,
        /** 비활성이거나 없는 제휴사 (400) */
        PARTNER_NOT_AVAILABLE,
        /** 요금이 0 이하 (400) */
        INVALID_FARE,
        /** 사용 포인트가 0 미만이거나 요금 초과 (400) */
        INVALID_POINT_AMOUNT,
        /** 허용되지 않는 상태 전이 (409) */
        ILLEGAL_STATE
    }

    private final Reason reason;

    public RideException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
