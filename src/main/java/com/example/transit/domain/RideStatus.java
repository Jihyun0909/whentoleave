package com.example.transit.domain;

/**
 * 택시 이용(가상)의 상태. 정상 흐름은 REQUESTED → IN_PROGRESS → COMPLETED.
 * REQUESTED / IN_PROGRESS에서만 CANCELLED로 갈 수 있다.
 */
public enum RideStatus {
    REQUESTED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    public boolean canTransitionTo(RideStatus next) {
        return switch (this) {
            case REQUESTED -> next == IN_PROGRESS || next == CANCELLED;
            case IN_PROGRESS -> next == COMPLETED || next == CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
