package com.example.transit.service.settlement;

/** 정산 실행 요청 자체가 잘못됐을 때(미래 날짜 등) → 400. */
public class InvalidSettlementRequestException extends RuntimeException {

    public InvalidSettlementRequestException(String message) {
        super(message);
    }
}
