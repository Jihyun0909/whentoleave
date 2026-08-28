package com.example.transit.domain;

/** 결제(가상)의 상태. 정산 실패 시 CANCELLED로 되돌린다(PR C). */
public enum PaymentStatus {
    PAID,
    CANCELLED
}
